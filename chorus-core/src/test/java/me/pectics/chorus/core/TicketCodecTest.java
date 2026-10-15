package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.crypto.Ed25519SignatureService;
import me.pectics.chorus.core.crypto.SigningKey;
import me.pectics.chorus.core.crypto.TicketCodec;
import me.pectics.chorus.core.crypto.VerificationKey;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.staticconfig.StaticRegistries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketCodecTest {
    private final KeyPair pair = TestSupport.keyPair();
    private final SigningKey key = TestSupport.signingKey(pair);
    private final TicketCodec codec = new TicketCodec(5120, 2048, new Ed25519SignatureService());
    private final StaticRegistries registries = TestSupport.registries(pair);

    private String part(int index) {
        byte[] bytes = codec.encode(TestSupport.claims(), key);
        return new String(
                Base64.getUrlDecoder()
                        .decode(new String(bytes, StandardCharsets.US_ASCII).split("\\.")[index]),
                StandardCharsets.UTF_8);
    }

    private void rejects(byte[] bytes, TicketError code) {
        assertThatThrownBy(() -> codec.decode(bytes, registries))
                .isInstanceOfSatisfying(
                        TicketRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    @Test
    void roundTripsNumericDateSecondsAndEnforcesFinalEncodedSize() {
        byte[] bytes = codec.encode(TestSupport.claims(), key);
        assertThat(codec.decode(bytes, registries).claims()).isEqualTo(TestSupport.claims());
        assertThat(part(1)).contains("\"iat\":" + TestSupport.NOW.getEpochSecond());
        assertThat(
                        new TicketCodec(bytes.length, 128, new Ed25519SignatureService())
                                .encode(TestSupport.claims(), key))
                .hasSize(bytes.length);
        assertThatThrownBy(
                        () ->
                                new TicketCodec(
                                                bytes.length - 1,
                                                128,
                                                new Ed25519SignatureService())
                                        .encode(TestSupport.claims(), key))
                .isInstanceOf(TicketRejectedException.class);
        rejects(new byte[5121], TicketError.TICKET_TOO_LARGE);
        rejects(null, TicketError.COOKIE_MISSING);
        rejects(new byte[0], TicketError.COOKIE_MISSING);
    }

    @Test
    void detectsTamperingAndDoesNotTrustAReplacementKey() {
        var attacker = TestSupport.keyPair();
        rejects(TestSupport.signRaw(part(0), part(1), attacker), TicketError.INVALID_SIGNATURE);
        String[] pieces =
                new String(codec.encode(TestSupport.claims(), key), StandardCharsets.US_ASCII)
                        .split("\\.");
        String changed =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                part(1).replace("Notch", "Other").getBytes(StandardCharsets.UTF_8));
        rejects(
                (pieces[0] + "." + changed + "." + pieces[2]).getBytes(StandardCharsets.US_ASCII),
                TicketError.INVALID_SIGNATURE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "EdDSA", "HS256", "Ed448"})
    void rejectsAlgorithmsOutsideTheSingleAllowlist(String algorithm) {
        rejects(
                TestSupport.signRaw(part(0).replace("Ed25519", algorithm), part(1), pair),
                TicketError.UNSUPPORTED_ALGORITHM);
    }

    @Test
    void rejectsUnknownIssuerKeyAndDisabledApproval() {
        rejects(
                TestSupport.signRaw(part(0), part(1).replace("gateway", "outsider"), pair),
                TicketError.UNKNOWN_ISSUER);
        rejects(
                TestSupport.signRaw(part(0).replace("key-1", "key-2"), part(1), pair),
                TicketError.UNTRUSTED_KEY);
        registries.replace(
                registries.snapshot(),
                List.of(
                        new TrustedIssuer(
                                "gateway",
                                new VerificationKey("key-1", pair.getPublic()),
                                false,
                                TestSupport.policy())));
        rejects(codec.encode(TestSupport.claims(), key), TicketError.UNTRUSTED_KEY);
    }

    @Test
    void acceptsOverlappingApprovedRotationKeysAndRejectsInvalidReplacementAtomically() {
        var second = TestSupport.keyPair();
        var old = registries.find("gateway", "key-1").orElseThrow();
        var rotated =
                new TrustedIssuer(
                        "gateway",
                        new VerificationKey("key-2", second.getPublic()),
                        true,
                        TestSupport.policy());
        registries.replace(registries.snapshot(), List.of(old, rotated));
        assertThat(
                        codec.decode(
                                        TestSupport.signRaw(
                                                part(0).replace("key-1", "key-2"), part(1), second),
                                        registries)
                                .claims())
                .isEqualTo(TestSupport.claims());
        assertThatThrownBy(() -> registries.replace(registries.snapshot(), List.of(old, old)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registries.find("gateway", "key-2")).contains(rotated);
    }

    @Test
    void rejectsMalformedFieldsDuplicatesAndUnsupportedFeatures() {
        String body = part(1);
        for (String bad :
                List.of(
                        body.replace("\"version\":1", "\"version\":1,\"version\":1"),
                        body.replace(
                                "\"username\":\"Notch\"",
                                "\"username\":\"Notch\",\"username\":\"Other\""),
                        body.replace(
                                "\"username\":\"Notch\"",
                                "\"username\":null,\"username\":\"Notch\""),
                        body.replace(
                                "\"iat\":" + TestSupport.NOW.getEpochSecond(), "\"iat\":\"123\""),
                        body.replace(
                                "\"iat\":" + TestSupport.NOW.getEpochSecond(), "\"iat\":123.5"),
                        body.replace("\"extensions\":{}", "\"extensions\":{\"custom:key\":\"v\"}"),
                        body.replace("textures", "admin"),
                        body.replace(
                                "\"extensions\":{}",
                                "\"extensions\":{\"nested\":"
                                        + "[".repeat(12)
                                        + "0"
                                        + "]".repeat(12)
                                        + "}"))) {
            rejects(TestSupport.signRaw(part(0), bad, pair), TicketError.MALFORMED_TICKET);
        }
        rejects(
                TestSupport.signRaw(part(0), body.replace("\"version\":1", "\"version\":2"), pair),
                TicketError.UNSUPPORTED_VERSION);
        rejects(
                TestSupport.signRaw(
                        part(0).replace("\"alg\":", "\"alg\":\"Ed25519\",\"alg\":"), body, pair),
                TicketError.MALFORMED_TICKET);
        rejects(
                TestSupport.signRaw(part(0).replace("}", ",\"b64\":false}"), body, pair),
                TicketError.MALFORMED_TICKET);
        rejects(
                TestSupport.signRaw(
                        part(0).replace("}", ",\"jku\":\"https://untrusted.example/keys\"}"),
                        body,
                        pair),
                TicketError.MALFORMED_TICKET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b", "a.b.c.d", ".b.c", "a=.b.c", "a.b.!!", "é.b.c"})
    void rejectsInvalidCompactEncoding(String compact) {
        rejects(compact.getBytes(StandardCharsets.UTF_8), TicketError.MALFORMED_TICKET);
    }
}
