package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.crypto.Ed25519SignatureService;
import me.pectics.chorus.core.crypto.SigningKey;
import me.pectics.chorus.core.crypto.StaticKeyRing;
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
        assertThat(part(0))
                .contains("\"alg\":\"Ed25519\"", "\"typ\":\"chorus-ticket+jwt\"")
                .doesNotContain("\"kid\"");
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
    void rejectsOutOfRangeNumericDatesAsMalformed() {
        String body =
                part(1).replace(
                                "\"iat\":" + TestSupport.NOW.getEpochSecond(),
                                "\"iat\":" + Long.MAX_VALUE);
        rejects(TestSupport.signRaw(part(0), body, pair), TicketError.MALFORMED_TICKET);
    }

    @Test
    void propertyLimitCountsUtf8BytesAndEmptySignaturesAreInvalid() {
        var identity =
                new me.pectics.chorus.core.model.IdentityClaim(
                        "Notch",
                        me.pectics.chorus.core.model.AuthType.MOJANG,
                        "mojang",
                        "account",
                        TestSupport.UUID_MOJANG,
                        List.of(
                                new me.pectics.chorus.core.model.ProfileProperty(
                                        "textures", "皮肤", null)));
        var claims =
                new me.pectics.chorus.core.model.TicketClaims(
                        1,
                        "bytes",
                        "gateway",
                        "backend",
                        TestSupport.NOW,
                        TestSupport.NOW,
                        TestSupport.NOW.plusSeconds(15),
                        identity,
                        Map.of());
        var exact = new TicketCodec(5120, 14, new Ed25519SignatureService());
        assertThat(exact.decode(exact.encode(claims, key), registries).claims()).isEqualTo(claims);
        assertThatThrownBy(
                        () ->
                                new TicketCodec(5120, 13, new Ed25519SignatureService())
                                        .encode(claims, key))
                .isInstanceOf(TicketRejectedException.class);
        assertThatThrownBy(
                        () ->
                                new me.pectics.chorus.core.model.ProfileProperty(
                                        "textures", "skin", ""))
                .isInstanceOf(IllegalArgumentException.class);
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
    void rejectsUnknownIssuerUnapprovedSignaturesAndDisabledApprovals() {
        rejects(
                TestSupport.signRaw(part(0), part(1).replace("gateway", "outsider"), pair),
                TicketError.UNKNOWN_ISSUER);
        rejects(
                TestSupport.signRaw(part(0), part(1), TestSupport.keyPair()),
                TicketError.INVALID_SIGNATURE);
        registries.replace(
                registries.snapshot(),
                List.of(
                        new TrustedIssuer(
                                "gateway",
                                List.of(new VerificationKey(pair.getPublic())),
                                false,
                                TestSupport.policy())));
        rejects(codec.encode(TestSupport.claims(), key), TicketError.UNTRUSTED_KEY);
    }

    @Test
    void acceptsOverlappingApprovedRotationKeysAndRejectsInvalidReplacementAtomically() {
        var second = TestSupport.keyPair();
        var old = registries.findByIssuer("gateway").orElseThrow();
        var newKey = new VerificationKey(second.getPublic());
        var rotated =
                new TrustedIssuer(
                        "gateway",
                        List.of(old.keys().getFirst(), newKey),
                        true,
                        TestSupport.policy());
        registries.replace(registries.snapshot(), List.of(rotated));
        var decoded = codec.decode(TestSupport.signRaw(part(0), part(1), second), registries);
        assertThat(decoded.claims()).isEqualTo(TestSupport.claims());
        assertThat(decoded.issuer()).isEqualTo(rotated);
        assertThat(codec.decode(codec.encode(TestSupport.claims(), key), registries).issuer())
                .isEqualTo(rotated);
        assertThat(new StaticKeyRing(key, registries).findTrustedKeys("gateway"))
                .containsExactly(old.keys().getFirst(), newKey);
        assertThatThrownBy(() -> registries.replace(registries.snapshot(), List.of(old, rotated)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registries.findByIssuer("gateway")).contains(rotated);
        assertThatThrownBy(() -> rotated.keys().add(newKey))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () ->
                                new TrustedIssuer(
                                        "gateway",
                                        List.of(newKey, newKey),
                                        true,
                                        TestSupport.policy()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disablesAllIssuerKeysAndDoesNotFallBackToOtherIssuers() {
        var second = TestSupport.keyPair();
        var firstKey = new VerificationKey(pair.getPublic());
        var secondKey = new VerificationKey(second.getPublic());
        var disabled =
                new TrustedIssuer(
                        "gateway", List.of(firstKey, secondKey), false, TestSupport.policy());
        var other = new TrustedIssuer("other", List.of(secondKey), true, TestSupport.policy());
        registries.replace(registries.snapshot(), List.of(disabled, other));
        rejects(codec.encode(TestSupport.claims(), key), TicketError.UNTRUSTED_KEY);
        rejects(TestSupport.signRaw(part(0), part(1), second), TicketError.UNTRUSTED_KEY);
        assertThat(new StaticKeyRing(null, registries).findTrustedKeys("gateway")).isEmpty();
        assertThat(new StaticKeyRing(null, registries).findTrustedKeys("unknown")).isEmpty();
        registries.replace(
                registries.snapshot(),
                List.of(
                        new TrustedIssuer("gateway", List.of(firstKey), true, TestSupport.policy()),
                        other));
        rejects(TestSupport.signRaw(part(0), part(1), second), TicketError.INVALID_SIGNATURE);
        assertThat(new StaticKeyRing(null, registries).findTrustedKeys("gateway"))
                .containsExactly(firstKey);
        rejects(
                TestSupport.signRaw(part(0), part(1).replace("gateway", "other"), pair),
                TicketError.INVALID_SIGNATURE);
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
                TestSupport.signRaw(part(0).replace("}", ",\"kid\":\"removed\"}"), body, pair),
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
