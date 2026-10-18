/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ (business config subpackages)
 * sync: Cover every business-owned section loader and shared value conversion without file I/O.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import me.pectics.chorus.core.crypto.TicketCryptoConfiguration;
import me.pectics.chorus.core.crypto.config.TicketCryptoConfigurationLoader;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.identity.BuiltinIdentityResolutionPolicy;
import me.pectics.chorus.core.node.ServerNodeConfiguration;
import me.pectics.chorus.core.node.ServerNodeRole;
import me.pectics.chorus.core.node.config.ServerNodeConfigurationLoader;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import me.pectics.chorus.core.replay.config.ReplayGuardConfigurationLoader;
import me.pectics.chorus.core.ticket.TicketCompression;
import me.pectics.chorus.core.ticket.config.TicketConfigurationLoader;
import me.pectics.chorus.core.ticket.config.TicketIssuanceConfigurationLoader;
import me.pectics.chorus.core.ticket.config.TicketVerificationConfigurationLoader;
import me.pectics.chorus.core.trust.IssuerTrustPolicy;
import me.pectics.chorus.core.trust.config.IssuerTrustPolicyConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConfigurationSectionLoaderTest {
    @TempDir Path directory;

    @Test
    void nodeSectionMapsOptionalDisplayNamesAndValidatesRole() {
        var loader = new ServerNodeConfigurationLoader();
        assertThat(
                        loader.load(
                                json(
                                        """
                                        {"id":"backend","display-name":"Backend","role":"PEER"}
                                        """),
                                directory))
                .isEqualTo(new ServerNodeConfiguration("backend", "Backend", ServerNodeRole.PEER));
        assertThat(loader.load(json("{\"id\":\"backend\",\"role\":\"BACKEND\"}"), directory))
                .isEqualTo(new ServerNodeConfiguration("backend", ServerNodeRole.BACKEND));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                loader.load(
                                        json(
                                                """
                                                {"id":"backend","display-name":" ","role":"BACKEND"}
                                                """),
                                        directory));
    }

    @ParameterizedTest
    @EnumSource(TicketCompression.class)
    void ticketSectionMapsEveryEncodingAndDefaultsToZstd(TicketCompression compression) {
        var loader = new TicketConfigurationLoader();
        String document =
                "{\"cookie-key\":\"chorus:ticket\",\"max-payload-bytes\":4096,"
                        + "\"max-property-bytes\":2048,\"max-lifetime\":\"15s\","
                        + "\"compression\":\""
                        + compression
                        + "\"}";
        assertThat(loader.load(json(document), directory).compression()).isEqualTo(compression);
        assertThat(
                        loader.load(
                                        json(
                                                """
                                                {"cookie-key":"chorus:ticket","max-payload-bytes":4096,
                                                 "max-property-bytes":2048,"max-lifetime":"15s"}
                                                """),
                                        directory)
                                .compression())
                .isEqualTo(TicketCompression.ZSTD);
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                loader.load(
                                        json(
                                                """
                                                {"cookie-key":"chorus:ticket","max-payload-bytes":4096,
                                                 "max-property-bytes":4097,"max-lifetime":"15s"}
                                                """),
                                        directory));
    }

    @Test
    void issuanceSectionLoadsLifetimePermissionsAndTargets() {
        var settings =
                new TicketIssuanceConfigurationLoader()
                        .load(
                                json(
                                        """
                                        {"ttl":"15000ms",
                                         "allowed-auth-schemes":["MOJANG","OFFLINE_AUTH"],
                                         "allowed-providers":["mojang","local"],
                                         "allowed-targets":["backend","next-backend"]}
                                        """),
                                directory);
        assertThat(settings.ttl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(settings.policy().identityAssertionPolicy().allowedAuthSchemes())
                .containsExactlyInAnyOrder(
                        AuthenticationSourceType.MOJANG, AuthenticationSourceType.OFFLINE_AUTH);
        assertThat(settings.policy().identityAssertionPolicy().allowedProviders())
                .containsExactlyInAnyOrder("mojang", "local");
        assertThat(settings.policy().allowedTargets())
                .containsExactlyInAnyOrder("backend", "next-backend");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new TicketIssuanceConfigurationLoader()
                                        .load(
                                                json(
                                                        """
                                                        {"ttl":"1500ms",
                                                         "allowed-auth-schemes":["MOJANG"],
                                                         "allowed-providers":["mojang"],
                                                         "allowed-targets":["backend"]}
                                                        """),
                                                directory));
    }

    @Test
    void verificationSectionLoadsFlagPolicyAndResolvedTrustPath() {
        var strict =
                new TicketVerificationConfigurationLoader()
                        .load(
                                json(
                                        """
                                        {"trusted-issuers-path":"trust/issuers.yml",
                                         "enforce-transfer-intent":false,
                                         "identity-policy":"STRICT_ONLINE_MATCH",
                                         "ip-binding":"DISABLED"}
                                        """),
                                directory);
        assertThat(strict.enforceTransferIntent()).isFalse();
        assertThat(strict.identityResolutionPolicy())
                .isEqualTo(BuiltinIdentityResolutionPolicy.STRICT_ONLINE_MATCH);
        assertThat(strict.trustedIssuersPath()).isEqualTo(directory.resolve("trust/issuers.yml"));
        var offline =
                new TicketVerificationConfigurationLoader()
                        .load(
                                json(
                                        """
                                        {"trusted-issuers-path":"issuers.yml",
                                         "enforce-transfer-intent":true,
                                         "identity-policy":"FORCE_OFFLINE_UUID"}
                                        """),
                                directory);
        assertThat(offline.identityResolutionPolicy())
                .isEqualTo(BuiltinIdentityResolutionPolicy.FORCE_OFFLINE_UUID);
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new TicketVerificationConfigurationLoader()
                                        .load(
                                                json(
                                                        """
                                                        {"trusted-issuers-path":"issuers.yml",
                                                         "enforce-transfer-intent":true,
                                                         "identity-policy":"UNKNOWN"}
                                                        """),
                                                directory));
    }

    @Test
    void cryptoSectionSelectsTheConfiguredAlgorithmShape() {
        var loader = new TicketCryptoConfigurationLoader();
        assertThat(
                        loader.load(
                                json(
                                        """
                                        {"algorithm":"ED25519","private-key":"keys/private.der",
                                         "public-key":"keys/public.der"}
                                        """),
                                directory))
                .isEqualTo(
                        new TicketCryptoConfiguration.Ed25519(
                                directory.resolve("keys/private.der"),
                                directory.resolve("keys/public.der")));
        assertThat(loader.load(json("{\"algorithm\":\"ED25519\"}"), directory))
                .isEqualTo(new TicketCryptoConfiguration.Ed25519(null, null));
        assertThat(
                        loader.load(
                                json(
                                        """
                                        {"algorithm":"HMAC_SHA256","key":"keys/shared.key"}
                                        """),
                                directory))
                .isEqualTo(
                        new TicketCryptoConfiguration.HmacSha256(
                                directory.resolve("keys/shared.key")));
        assertThat(loader.load(json("{\"algorithm\":\"HMAC_SHA256\"}"), directory))
                .isEqualTo(new TicketCryptoConfiguration.HmacSha256(null));
    }

    @Test
    void issuerTrustPolicySectionAcceptsEveryDocumentedScheme() {
        var policy =
                new IssuerTrustPolicyConfigurationLoader()
                        .load(
                                json(
                                        """
                                        {"allowed-auth-schemes":
                                          ["MOJANG","OFFLINE_AUTH","OFFLINE_RAW","YGGDRASIL","FLOODGATE"],
                                         "allowed-providers":["mojang","operator-b/local"]}
                                        """),
                                directory);
        assertThat(policy.allowedAuthSchemes())
                .containsExactlyInAnyOrder(AuthenticationSourceType.values());
        assertThat(policy.allowedProviders())
                .containsExactlyInAnyOrder("mojang", "operator-b/local");
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new IssuerTrustPolicyConfigurationLoader()
                                        .load(
                                                json(
                                                        """
                                                        {"allowed-auth-schemes":["MOJANG"],
                                                         "allowed-providers":["Mojang"]}
                                                        """),
                                                directory));
    }

    @Test
    void replaySectionLoadsLocalAndSharedBackends() {
        var loader = new ReplayGuardConfigurationLoader();
        var local =
                loader.load(
                        json(
                                """
                                {"backend":"LOCAL",
                                 "local":{"path":"state/replay.db",
                                          "busy-timeout":"750ms"}}
                                """),
                        directory);
        assertThat(local.backend()).isEqualTo(ReplayGuardConfiguration.Backend.LOCAL);
        assertThat(local.local().path()).isEqualTo(directory.resolve("state/replay.db"));
        assertThat(local.local().busyTimeout()).isEqualTo(Duration.ofMillis(750));
        assertThat(local.local().path()).doesNotExist();

        var registry = loader.load(json("{\"backend\":\"REGISTRY\"}"), directory);
        assertThat(registry.backend()).isEqualTo(ReplayGuardConfiguration.Backend.REGISTRY);
        assertThat(registry.local()).isNull();
        assertThat(registry.redis()).isNull();

        var redis =
                loader.load(
                        json(
                                """
                                {"backend":"REDIS",
                                 "redis":{"host":"localhost","port":6379,"database":2,
                                          "username":"chorus",
                                          "password-path":"secrets/redis.txt",
                                          "tls":{"mode":"DISABLED"},
                                          "timeouts":{"connect":"2s","command":"3s",
                                                      "acquire":"500ms"},
                                          "pool":{"size":4}}}
                                """),
                        directory);
        assertThat(redis.backend()).isEqualTo(ReplayGuardConfiguration.Backend.REDIS);
        assertThat(redis.redis().passwordPath()).isEqualTo(directory.resolve("secrets/redis.txt"));
        assertThat(redis.redis().timeouts().acquire()).isEqualTo(Duration.ofMillis(500));
        assertThat(redis.redis().pool().size()).isEqualTo(4);
        assertThat(redis.redis().tls().mode()).isEqualTo(ReplayGuardConfiguration.TlsMode.DISABLED);
    }

    @Test
    void redisTlsSectionResolvesAnOptionalCaCertificate() {
        var loader = new ReplayGuardConfigurationLoader();
        var base =
                """
                {"backend":"REDIS","redis":{"host":"localhost","port":6379,"database":0,
                 "username":"chorus","password-path":"redis-password.txt",
                 "tls":{"mode":"VERIFY_FULL"%s},
                 "timeouts":{"connect":"2s","command":"2s","acquire":"500ms"},
                 "pool":{"size":2}}}
                """;
        assertThat(loader.load(json(base.formatted("")), directory).redis().tls().caCert())
                .isNull();
        assertThat(
                        loader.load(json(base.formatted(",\"ca-cert\":\"ca.pem\"")), directory)
                                .redis()
                                .tls()
                                .caCert())
                .isEqualTo(directory.resolve("ca.pem"));
    }

    @Test
    void valueParserConvertsDurationsPathsAndStringSetsWithoutTouchingFiles() {
        for (var entry :
                List.of(
                        new Object[] {"1500ms", Duration.ofMillis(1500)},
                        new Object[] {"2s", Duration.ofSeconds(2)},
                        new Object[] {"3m", Duration.ofMinutes(3)},
                        new Object[] {"4h", Duration.ofHours(4)},
                        new Object[] {"PT1H30M", Duration.ofMinutes(90)})) {
            assertThat(ConfigurationValueParser.duration(json("\"" + entry[0] + "\"")))
                    .isEqualTo(entry[1]);
        }
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                ConfigurationValueParser.duration(
                                        json("\"999999999999999999999h\"")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigurationValueParser.duration(json("\"tomorrow\"")));

        var base = directory.resolve("base");
        assertThat(
                        ConfigurationValueParser.optionalPath(
                                json("{\"key\":\"keys/public.der\"}"), "key", base))
                .isEqualTo(base.resolve("keys/public.der"));
        assertThat(
                        ConfigurationValueParser.optionalPath(
                                json("{\"algorithm\":\"ED25519\"}"), "key", base))
                .isNull();
        assertThat(ConfigurationValueParser.strings(json("[\"a\",\"b\"]")))
                .containsExactlyInAnyOrder("a", "b");
        assertThatThrownBy(() -> ConfigurationValueParser.strings(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(base).doesNotExist();
    }

    @Test
    void trustAndIssuancePoliciesShareOnePermissionConversion() {
        var section =
                json(
                        """
                        {"allowed-auth-schemes":["OFFLINE_AUTH"],
                         "allowed-providers":["operator-b/local"]}
                        """);
        assertThat(new IssuerTrustPolicyConfigurationLoader().load(section, directory))
                .isEqualTo(
                        new IssuerTrustPolicy(
                                Set.of(AuthenticationSourceType.OFFLINE_AUTH),
                                Set.of("operator-b/local")));
    }

    private static JsonNode json(String value) {
        return JsonMapper.shared().readTree(value);
    }
}
