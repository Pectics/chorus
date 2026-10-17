/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2, 3.7, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/bootstrap/ChorusCoreBootstrap.java;
 *          chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/trusted-issuers.schema.json
 * sync: Validate trust documents before signer assembly; only read administrator-provided key files.
 * @endcontext
 */
package me.pectics.chorus.core.trust.config;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import me.pectics.chorus.core.config.ConfigurationLoader;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader.ConfigurationDocumentType;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketVerifier;
import me.pectics.chorus.core.trust.TrustedIssuer;
import me.pectics.chorus.core.trust.TrustedIssuerConfiguration;
import me.pectics.chorus.core.trust.file.FileTrustedIssuerStore;
import tools.jackson.databind.JsonNode;

/**
 * Stateless local approval loading; immutable settings are validated before any key file is read.
 */
public final class ConfigTrustedIssuerLoader
        implements ConfigurationLoader<List<TrustedIssuerConfiguration>> {
    @Override
    public List<TrustedIssuerConfiguration> load(Path file) throws IOException {
        var document =
                new YamlConfigurationLoader(ConfigurationDocumentType.TRUSTED_ISSUERS).load(file);
        Path base = file.toAbsolutePath().normalize().getParent();
        var configurations = new ArrayList<TrustedIssuerConfiguration>();
        for (var entry : document.properties()) {
            var value = entry.getValue();
            var algorithm =
                    value.has("algorithm")
                            ? SignatureAlgorithm.valueOf(value.get("algorithm").asString())
                            : SignatureAlgorithm.ED25519;
            Iterable<JsonNode> paths =
                    value.has("keys") ? value.get("keys") : List.of(value.get("key"));
            var keyPaths = new ArrayList<Path>();
            for (var key : paths) keyPaths.add(base.resolve(key.asString()));
            configurations.add(
                    new TrustedIssuerConfiguration(
                            entry.getKey(),
                            algorithm,
                            keyPaths,
                            !value.has("enabled") || value.get("enabled").asBoolean(),
                            new IssuerTrustPolicyConfigurationLoader().load(value, base)));
        }
        return List.copyOf(configurations);
    }

    /**
     * Reads the approved key files without creating missing files or publishing approvals. Returns
     * an immutable issuer snapshot. Invoke outside event loops; null configuration values throw
     * NullPointerException, invalid key bindings throw IllegalArgumentException, file failures
     * throw IOException, and invalid cryptographic material throws GeneralSecurityException.
     */
    public static List<TrustedIssuer> loadIssuers(List<TrustedIssuerConfiguration> configurations)
            throws IOException, GeneralSecurityException {
        var issuers = new ArrayList<TrustedIssuer>();
        for (var configuration : List.copyOf(configurations)) {
            var verifiers = new ArrayList<TicketVerifier>();
            for (var path : configuration.keyPaths()) {
                verifiers.add(
                        switch (configuration.algorithm()) {
                            case ED25519 -> Ed25519.loadVerifier(path);
                            case HMAC_SHA256 -> HmacSha256.load(path);
                        });
            }
            issuers.add(
                    new TrustedIssuer(
                            configuration.issuerNodeId(),
                            verifiers,
                            configuration.enabled(),
                            configuration.policy()));
        }
        return List.copyOf(issuers);
    }

    /**
     * Loads an independent approval store; file and key failures follow load/loadIssuers contracts.
     */
    public static FileTrustedIssuerStore loadStore(Path file)
            throws IOException, GeneralSecurityException {
        return new FileTrustedIssuerStore(loadIssuers(new ConfigTrustedIssuerLoader().load(file)));
    }

    /**
     * Reloads all approvals atomically; invalid files or keys preserve the previous store snapshot.
     */
    public static void reloadStore(FileTrustedIssuerStore store, Path file)
            throws IOException, GeneralSecurityException {
        store.replace(loadIssuers(new ConfigTrustedIssuerLoader().load(file)));
    }
}
