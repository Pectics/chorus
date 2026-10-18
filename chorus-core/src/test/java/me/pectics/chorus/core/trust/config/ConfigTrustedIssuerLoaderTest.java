/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.7 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/trust/config/ConfigTrustedIssuerLoader.java
 * sync: Approval parsing precedes signing-key creation; approval key loading never creates secrets.
 * @endcontext
 */
package me.pectics.chorus.core.trust.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import me.pectics.chorus.core.config.ConfigurationLoader;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.trust.TrustedIssuerConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTrustedIssuerLoaderTest {
    @TempDir Path directory;

    @Test
    void enabledDefaultsToTrueAndExplicitValuesArePreserved() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("issuers.yml"),
                        """
                        implicit:
                          algorithm: HMAC_SHA256
                          key: implicit.key
                          allowed-auth-schemes: [MOJANG]
                          allowed-providers: [mojang]
                        explicit:
                          algorithm: HMAC_SHA256
                          key: explicit.key
                          enabled: true
                          allowed-auth-schemes: [MOJANG]
                          allowed-providers: [mojang]
                        disabled:
                          algorithm: HMAC_SHA256
                          key: disabled.key
                          enabled: false
                          allowed-auth-schemes: [MOJANG]
                          allowed-providers: [mojang]
                        """);
        var approvals = new ConfigTrustedIssuerLoader().load(file);
        assertThat(approvals)
                .extracting(
                        TrustedIssuerConfiguration::issuerNodeId,
                        TrustedIssuerConfiguration::enabled)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("implicit", true),
                        org.assertj.core.groups.Tuple.tuple("explicit", true),
                        org.assertj.core.groups.Tuple.tuple("disabled", false));
    }

    @Test
    void approvalParsingDefersKeyReadsWithoutCreatingMissingSecrets() throws Exception {
        Path trustDirectory = Files.createDirectory(directory.resolve("trust"));
        Path file =
                Files.writeString(
                        trustDirectory.resolve("issuers.yml"),
                        """
                        gateway:
                          algorithm: HMAC_SHA256
                          key: keys/gateway.key
                          allowed-auth-schemes: [MOJANG]
                          allowed-providers: [mojang]
                        """);
        ConfigurationLoader<List<TrustedIssuerConfiguration>> loader =
                new ConfigTrustedIssuerLoader();
        var settings = loader.load(file);
        Path keyPath = trustDirectory.resolve("keys/gateway.key");
        assertThat(settings)
                .singleElement()
                .satisfies(
                        approval -> {
                            assertThat(approval.issuerNodeId()).isEqualTo("gateway");
                            assertThat(approval.algorithm())
                                    .isEqualTo(SignatureAlgorithm.HMAC_SHA256);
                            assertThat(approval.keyPaths()).containsExactly(keyPath);
                            assertThat(approval.enabled()).isTrue();
                            assertThat(approval.policy().allowedAuthSchemes())
                                    .containsExactly(AuthenticationSourceType.MOJANG);
                            assertThat(approval.policy().allowedProviders())
                                    .containsExactly("mojang");
                        });
        assertThatThrownBy(settings::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(settings.getFirst().keyPaths()::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(keyPath.getParent()).doesNotExist();
        assertThatThrownBy(() -> ConfigTrustedIssuerLoader.loadIssuers(settings))
                .isInstanceOf(IOException.class);
        assertThat(keyPath.getParent()).doesNotExist();

        var signer = HmacSha256.loadOrCreate(keyPath);
        var issuers = ConfigTrustedIssuerLoader.loadIssuers(settings);
        byte[] payload = "approved-ticket".getBytes(StandardCharsets.UTF_8);
        assertThat(issuers)
                .singleElement()
                .satisfies(
                        issuer -> {
                            assertThat(issuer.issuerNodeId()).isEqualTo("gateway");
                            assertThat(issuer.policy()).isEqualTo(settings.getFirst().policy());
                            assertThat(
                                            issuer.verifiers()
                                                    .getFirst()
                                                    .verify(payload, signer.sign(payload)))
                                    .isTrue();
                        });
    }
}
