package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.ticket.TicketClaims;
import me.pectics.chorus.core.ticket.TicketCodec;
import me.pectics.chorus.core.ticket.TicketCompression;
import me.pectics.chorus.core.trust.TrustedIssuer;
import me.pectics.chorus.core.trust.file.FileTrustedIssuerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketCodecTest {
    private final KeyPair pair = TestSupport.keyPair();
    private final TicketSigner key = TestSupport.signer(pair);
    private final TicketCodec codec = new TicketCodec(5120, 2048, TicketCompression.NONE);
    private final FileTrustedIssuerStore issuers = TestSupport.issuers(pair);

    private String part(int index) {
        byte[] bytes = codec.encode(TestSupport.claims(), key);
        return new String(
                Base64.getUrlDecoder()
                        .decode(new String(bytes, StandardCharsets.US_ASCII).split("\\.")[index]),
                StandardCharsets.UTF_8);
    }

    private void rejects(byte[] bytes, TicketError code) {
        assertThatThrownBy(() -> codec.decode(bytes, issuers))
                .isInstanceOfSatisfying(
                        TicketRejectedException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    @Test
    void roundTripsNumericDateSecondsAndEnforcesFinalEncodedSize() {
        byte[] bytes = codec.encode(TestSupport.claims(), key);
        assertThat(codec.decode(bytes, issuers).claims()).isEqualTo(TestSupport.claims());
        assertThat(part(0))
                .contains("\"alg\":\"Ed25519\"", "\"typ\":\"chorus-ticket+jwt\"")
                .doesNotContain("\"kid\"");
        assertThat(part(1)).contains("\"iat\":" + TestSupport.NOW.getEpochSecond());
        assertThat(
                        new TicketCodec(bytes.length, 128, TicketCompression.NONE)
                                .encode(TestSupport.claims(), key))
                .hasSize(bytes.length);
        assertThatThrownBy(
                        () ->
                                new TicketCodec(bytes.length - 1, 128, TicketCompression.NONE)
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
                new me.pectics.chorus.core.identity.IdentityClaim(
                        "Notch",
                        me.pectics.chorus.core.identity.AuthenticationSourceType.MOJANG,
                        "mojang",
                        "account",
                        TestSupport.UUID_MOJANG,
                        List.of(
                                new me.pectics.chorus.core.identity.ProfileProperty(
                                        "textures", "皮肤", null)));
        var claims =
                new me.pectics.chorus.core.ticket.TicketClaims(
                        1,
                        "bytes",
                        "gateway",
                        "backend",
                        TestSupport.NOW,
                        TestSupport.NOW,
                        TestSupport.NOW.plusSeconds(15),
                        identity,
                        Map.of());
        var exact = new TicketCodec(5120, 14);
        assertThat(exact.decode(exact.encode(claims, key), issuers).claims()).isEqualTo(claims);
        assertThatThrownBy(() -> new TicketCodec(5120, 13).encode(claims, key))
                .isInstanceOf(TicketRejectedException.class);
        assertThatThrownBy(
                        () ->
                                new me.pectics.chorus.core.identity.ProfileProperty(
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
    void rejectsAlgorithmsOutsideTheIssuerAllowlist(String algorithm) {
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
        issuers.replace(
                List.of(
                        new TrustedIssuer(
                                "gateway",
                                List.of(new Ed25519.Verifier(pair.getPublic())),
                                false,
                                TestSupport.policy())));
        rejects(codec.encode(TestSupport.claims(), key), TicketError.UNTRUSTED_KEY);
    }

    @Test
    void acceptsOverlappingApprovedRotationKeysAndRejectsInvalidReplacementAtomically() {
        var second = TestSupport.keyPair();
        var old = issuers.findByIssuer("gateway").orElseThrow();
        var newKey = new Ed25519.Verifier(second.getPublic());
        var rotated =
                new TrustedIssuer(
                        "gateway",
                        List.of(old.verifiers().getFirst(), newKey),
                        true,
                        TestSupport.policy());
        issuers.replace(List.of(rotated));
        var decoded = codec.decode(TestSupport.signRaw(part(0), part(1), second), issuers);
        assertThat(decoded.claims()).isEqualTo(TestSupport.claims());
        assertThat(decoded.issuer()).isEqualTo(rotated);
        assertThat(codec.decode(codec.encode(TestSupport.claims(), key), issuers).issuer())
                .isEqualTo(rotated);
        assertThatThrownBy(() -> issuers.replace(List.of(old, rotated)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(issuers.findByIssuer("gateway")).contains(rotated);
        assertThatThrownBy(() -> rotated.verifiers().add(newKey))
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
        var firstKey = new Ed25519.Verifier(pair.getPublic());
        var secondKey = new Ed25519.Verifier(second.getPublic());
        var disabled =
                new TrustedIssuer(
                        "gateway", List.of(firstKey, secondKey), false, TestSupport.policy());
        var other = new TrustedIssuer("other", List.of(secondKey), true, TestSupport.policy());
        issuers.replace(List.of(disabled, other));
        rejects(codec.encode(TestSupport.claims(), key), TicketError.UNTRUSTED_KEY);
        rejects(TestSupport.signRaw(part(0), part(1), second), TicketError.UNTRUSTED_KEY);
        issuers.replace(
                List.of(
                        new TrustedIssuer("gateway", List.of(firstKey), true, TestSupport.policy()),
                        other));
        rejects(TestSupport.signRaw(part(0), part(1), second), TicketError.INVALID_SIGNATURE);
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

    @Test
    void encodingRejectsUnsupportedVersionsAndSubSecondTimestamps() {
        assertThatThrownBy(
                        () ->
                                codec.encode(
                                        new TicketClaims(
                                                2,
                                                "jti",
                                                "gateway",
                                                "backend",
                                                TestSupport.NOW,
                                                TestSupport.NOW,
                                                TestSupport.NOW.plusSeconds(15),
                                                TestSupport.identity(),
                                                Map.of()),
                                        key))
                .isInstanceOfSatisfying(
                        TicketRejectedException.class,
                        rejection ->
                                assertThat(rejection.code())
                                        .isEqualTo(TicketError.UNSUPPORTED_VERSION));
        assertThatThrownBy(
                        () ->
                                codec.encode(
                                        new TicketClaims(
                                                1,
                                                "jti",
                                                "gateway",
                                                "backend",
                                                TestSupport.NOW.plusNanos(1),
                                                TestSupport.NOW,
                                                TestSupport.NOW.plusSeconds(15),
                                                TestSupport.identity(),
                                                Map.of()),
                                        key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
    }

    @Test
    void rejectsHeaderShapeBeyondTheFixedAlgorithmAndType() {
        String body = part(1);
        for (String header :
                List.of(
                        "{\"alg\":\"Ed25519\"}",
                        "{\"alg\":\"Ed25519\",\"typ\":\"JWT\"}",
                        "{\"alg\":\"Ed25519\",\"typ\":\"chorus-ticket+jwt\",\"kid\":\"x\"}",
                        "{\"alg\":\"Ed25519\",\"typ\":\"chorus-ticket+jwt\",\"crit\":[]}",
                        "{\"alg\":\"Ed25519\",\"alg\":\"Ed25519\",\"typ\":\"chorus-ticket+jwt\"}")) {
            rejects(TestSupport.signRaw(header, body, pair), TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void rejectsInvalidIdentityAndClaimWireValuesAfterSignatureVerification() {
        String header = part(0);
        String body = part(1);
        String uuid = TestSupport.UUID_MOJANG.toString();
        for (String invalid :
                List.of(
                        body.replace("\"authType\":\"MOJANG\"", "\"authType\":\"UNKNOWN\""),
                        body.replace(uuid, uuid.toUpperCase(Locale.ROOT)),
                        body.replace(uuid, "not-a-uuid"),
                        body.replace("\"username\":\"Notch\"", "\"username\":\"bad-name\""),
                        body.replace(
                                "\"username\":\"Notch\"", "\"username\":\"Notch\",\"extra\":1"),
                        body.replace("\"extensions\":{}", "\"extensions\":[]"),
                        body.replace("\"value\":\"skin\"", "\"value\":\"\""),
                        body.replace("\"jti\":\"ticket-1\"", "\"jti\":\"" + "x".repeat(129) + "\""),
                        body.replace("\"aud\":\"backend\"", "\"aud\":\"\""),
                        body.replace("\"iss\":\"gateway\"", "\"iss\":\"\""))) {
            rejects(TestSupport.signRaw(header, invalid, pair), TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void rejectsMalformedJsonContainersAndUnbalancedScopes() {
        String header = part(0);
        String body = part(1);
        for (String invalid :
                List.of(
                        "not-json",
                        body.substring(0, body.length() - 1),
                        body + "]",
                        body.replace("\"Notch\"", "\"Notch"),
                        body.replace("\"extensions\":{}", "\"extensions\":{},"),
                        body.replace("\"signature\":\"signature\"", "\"signature\":null"),
                        body.replace("{\"name\":", "[{\"name\":"))) {
            rejects(TestSupport.signRaw(header, invalid, pair), TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void rejectsMissingExtraAndMalformedIdentityClaims() {
        String header = part(0);
        String body = part(1);
        for (String invalid :
                List.of(
                        body.substring(0, body.length() - 1) + ",\"unknown\":1}",
                        body.replace(
                                "\"authProvider\":\"mojang\"",
                                "\"authProvider\":\"mojang\",\"unknown\":1"),
                        body.replace("\"authProvider\":\"mojang\"", "\"authProvider\":\"\""),
                        body.replace(
                                "\"providerSubject\":\"" + TestSupport.UUID_MOJANG + "\"",
                                "\"providerSubject\":\"\""),
                        body.replace(
                                "\"sourceUuid\":\"" + TestSupport.UUID_MOJANG + "\"",
                                "\"sourceUuid\":\"\""),
                        body.replace(",\"value\":\"skin\"", ""),
                        body.replace("\"value\":\"skin\"", "\"value\":\"skin\",\"extra\":\"x\""),
                        body.replace(
                                "\"properties\":[{",
                                "\"properties\":[{\"name\":\"textures\",\"value\":\"a\"},{"),
                        body.replace(
                                "\"signature\":\"signature\"",
                                "\"signature\":\"signature\",\"name\":\"textures\""))) {
            rejects(TestSupport.signRaw(header, invalid, pair), TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void enforcesTheExactPayloadBoundaryForDecoding() {
        var exact = codec.encode(TestSupport.claims(), key);
        assertThat(
                        new TicketCodec(
                                        exact.length,
                                        Math.min(2048, exact.length),
                                        TicketCompression.NONE)
                                .decode(exact, issuers)
                                .claims())
                .isEqualTo(TestSupport.claims());
        rejects(new byte[exact.length - 1], TicketError.MALFORMED_TICKET);
        byte[] maximum = new byte[5120];
        java.util.Arrays.fill(maximum, (byte) 'a');
        rejects(maximum, TicketError.MALFORMED_TICKET);
        byte[] oversized = new byte[5121];
        java.util.Arrays.fill(oversized, (byte) 'a');
        rejects(oversized, TicketError.TICKET_TOO_LARGE);
    }

    @Test
    void propertyBudgetCountsNameValueAndSignatureTogether() {
        var claims = TestSupport.claims();
        var exact = new TicketCodec(5120, 8 + 4 + 9);
        assertThat(exact.decode(exact.encode(claims, key), issuers).claims()).isEqualTo(claims);
        assertThatThrownBy(() -> new TicketCodec(5120, 8 + 4 + 9 - 1).encode(claims, key))
                .isInstanceOf(TicketRejectedException.class);
    }

    @Test
    void acceptsEmptyPropertyListsAndEscapedProviderSubjects() {
        var emptyProperties =
                new me.pectics.chorus.core.identity.IdentityClaim(
                        "Notch",
                        me.pectics.chorus.core.identity.AuthenticationSourceType.MOJANG,
                        "mojang",
                        "subject",
                        TestSupport.UUID_MOJANG,
                        List.of());
        var escapedSubject =
                new me.pectics.chorus.core.identity.IdentityClaim(
                        "Notch",
                        me.pectics.chorus.core.identity.AuthenticationSourceType.MOJANG,
                        "mojang",
                        "subject\"with\\escapes",
                        TestSupport.UUID_MOJANG,
                        List.of());
        for (var identity : List.of(emptyProperties, escapedSubject)) {
            var claims =
                    new TicketClaims(
                            1,
                            "escaped",
                            "gateway",
                            "backend",
                            TestSupport.NOW,
                            TestSupport.NOW,
                            TestSupport.NOW.plusSeconds(15),
                            identity,
                            Map.of());
            assertThat(codec.decode(codec.encode(claims, key), issuers).claims()).isEqualTo(claims);
        }
    }

    @Test
    void rejectsNonStringFieldsAndDuplicateNestedKeys() {
        String header = part(0);
        String body = part(1);
        for (String invalid :
                List.of(
                        body.replace("\"jti\":\"ticket-1\"", "\"jti\":1"),
                        body.replace("\"iss\":\"gateway\"", "\"iss\":false"),
                        body.replace("\"aud\":\"backend\"", "\"aud\":[]"),
                        body.replace("\"username\":\"Notch\"", "\"username\":1"),
                        body.replace("\"iat\":" + TestSupport.NOW.getEpochSecond(), "\"iat\":true"),
                        body.replace(
                                "\"username\":\"Notch\"",
                                "\"username\":\"Notch\",\"username\":\"Other\""),
                        body.replace(
                                "\"value\":\"skin\"", "\"value\":\"skin\",\"value\":\"other\""),
                        body.replace("\"properties\":[", "\"properties\":[\"texture\","))) {
            rejects(TestSupport.signRaw(header, invalid, pair), TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void detectsDuplicateKeysThatOnlyCollideAfterUnescaping() {
        String header = part(0);
        String body = part(1);
        for (String invalid :
                List.of(
                        body.replace("\"version\":1", "\"version\":1,\"versio\\u006e\":1"),
                        body.replace(
                                "\"username\":\"Notch\"",
                                "\"username\":\"Notch\",\"user\\u006eame\":\"Other\""),
                        body.replace(
                                "\"value\":\"skin\"",
                                "\"value\":\"skin\",\"valu\\u0065\":\"other\""))) {
            rejects(TestSupport.signRaw(header, invalid, pair), TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void constructorsRejectInvalidPayloadAndPropertyBudgets() {
        assertThat(new TicketCodec(5120, 5120, TicketCompression.NONE)).isNotNull();
        assertThat(new TicketCodec(1, 1, TicketCompression.NONE)).isNotNull();
        for (int[] limits :
                new int[][] {
                    {0, 0},
                    {-1, 1},
                    {5121, 1},
                    {4096, 0},
                    {4096, -1},
                    {4096, 4097},
                    {1, 2}
                }) {
            assertThatThrownBy(() -> new TicketCodec(limits[0], limits[1], TicketCompression.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new TicketCodec(4096, 2048, null))
                .isInstanceOf(NullPointerException.class);
    }
}
