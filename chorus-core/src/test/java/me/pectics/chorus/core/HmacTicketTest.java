/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.4 and 3.7
 * sync: Exercise HS256 interoperability and reject issuer or algorithm substitution.
 * @endcontext
 */
package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.MACVerifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.TicketCodec;
import me.pectics.chorus.core.crypto.TicketVerifier;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.config.ConfigRegistries;
import org.junit.jupiter.api.Test;

class HmacTicketTest {
    private final TicketCodec codec = new TicketCodec(5120, 2048);
    private final HmacSha256 key = key((byte) 1);
    private final ConfigRegistries registries = TestSupport.registries(TestSupport.keyPair());

    private static HmacSha256 key(byte value) {
        return new HmacSha256(secretBytes(value));
    }

    private static byte[] secretBytes(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private void approve(TicketVerifier... verifiers) {
        registries.replace(
                registries.snapshot(),
                List.of(
                        new TrustedIssuer(
                                "gateway", List.of(verifiers), true, TestSupport.policy())));
    }

    private void rejects(byte[] ticket, TicketError error) {
        assertThatThrownBy(() -> codec.decode(ticket, registries))
                .isInstanceOfSatisfying(
                        TicketRejectedException.class,
                        rejection -> assertThat(rejection.code()).isEqualTo(error));
    }

    @Test
    void matchesIndependentHmacSha256TestVectorAndCopiesSecretBytes() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 0x0b);
        var secret = new HmacSha256(bytes);
        bytes[0] = 0;
        byte[] data = "Hi There".getBytes(StandardCharsets.US_ASCII);
        byte[] expected =
                HexFormat.of()
                        .parseHex(
                                "198a607eb44bfbc69903a0f1cf2bbdc5ba0aa3f3d9ae3c1c7a3b1696a0b68cf7");
        assertThat(secret.sign(data)).isEqualTo(expected);
        assertThat(secret.verify(data, expected)).isTrue();
        expected[0] ^= 1;
        assertThat(secret.verify(data, expected)).isFalse();
        assertThat(secret.verify(data, new byte[31])).isFalse();
        assertThat(secret.verify(data, new byte[33])).isFalse();
        assertThat(secret.toString()).isEqualTo("HmacSha256[redacted]");
        assertThatThrownBy(() -> new HmacSha256(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsHs256AndInteroperatesWithNimbus() throws Exception {
        approve(key);
        byte[] ticket = codec.encode(TestSupport.claims(), key);
        var parsed = JWSObject.parse(new String(ticket, StandardCharsets.US_ASCII));
        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(parsed.getHeader().getType().toString()).isEqualTo("chorus-ticket+jwt");
        assertThat(parsed.getHeader().getKeyID()).isNull();
        assertThat(parsed.verify(new MACVerifier(secretBytes((byte) 1)))).isTrue();
        assertThat(codec.decode(ticket, registries).claims()).isEqualTo(TestSupport.claims());
        var signed = new JWSObject(parsed.getHeader(), parsed.getPayload());
        signed.sign(new com.nimbusds.jose.crypto.MACSigner(secretBytes((byte) 1)));
        assertThat(
                        codec.decode(
                                        signed.serialize().getBytes(StandardCharsets.US_ASCII),
                                        registries)
                                .claims())
                .isEqualTo(TestSupport.claims());
    }

    @Test
    void rejectsWrongKeysTamperingAndRevokedRotationKeys() {
        approve(key);
        byte[] ticket = codec.encode(TestSupport.claims(), key);
        rejects(codec.encode(TestSupport.claims(), key((byte) 2)), TicketError.INVALID_SIGNATURE);
        String[] parts = new String(ticket, StandardCharsets.US_ASCII).split("\\.");
        String body = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        parts[1] =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                body.replace("Notch", "Other").getBytes(StandardCharsets.UTF_8));
        rejects(
                String.join(".", parts).getBytes(StandardCharsets.US_ASCII),
                TicketError.INVALID_SIGNATURE);
        var rotated = key((byte) 3);
        approve(key, rotated);
        assertThat(codec.decode(ticket, registries).claims()).isEqualTo(TestSupport.claims());
        assertThat(codec.decode(codec.encode(TestSupport.claims(), rotated), registries).claims())
                .isEqualTo(TestSupport.claims());
        approve(rotated);
        rejects(ticket, TicketError.INVALID_SIGNATURE);
    }

    @Test
    void cannotUseAnEd25519PublicKeyAsAnHs256Secret() throws Exception {
        var pair = TestSupport.keyPair();
        approve(new Ed25519.Verifier(pair.getPublic()));
        byte[] ticket = codec.encode(TestSupport.claims(), key);
        String compact = new String(ticket, StandardCharsets.US_ASCII);
        String input = compact.substring(0, compact.lastIndexOf('.'));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(pair.getPublic().getEncoded(), "HmacSHA256"));
        byte[] attack =
                (input
                                + "."
                                + Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(
                                                mac.doFinal(
                                                        input.getBytes(StandardCharsets.US_ASCII))))
                        .getBytes(StandardCharsets.US_ASCII);
        rejects(attack, TicketError.UNSUPPORTED_ALGORITHM);
        approve(key);
        rejects(
                codec.encode(TestSupport.claims(), TestSupport.signer(pair)),
                TicketError.UNSUPPORTED_ALGORITHM);
        assertThatThrownBy(
                        () ->
                                new TrustedIssuer(
                                        "gateway",
                                        List.of(key, new Ed25519.Verifier(pair.getPublic())),
                                        true,
                                        TestSupport.policy()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
