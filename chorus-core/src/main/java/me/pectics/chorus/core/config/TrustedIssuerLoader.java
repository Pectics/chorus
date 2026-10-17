/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2, 3.7, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/CoreBootstrap.java;
 *          chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/trusted-issuers.schema.json
 * sync: Validate trust documents before signer assembly; only read administrator-provided key files.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import me.pectics.chorus.core.config.YamlConfigurationLoader.ConfigurationDocumentType;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketVerifier;
import me.pectics.chorus.core.trust.TrustedIssuer;
import tools.jackson.databind.JsonNode;

/** Validated local trust document; key reads are deferred until local signer assembly completes. */
record TrustedIssuerLoader(JsonNode document, Path file) {
    static TrustedIssuerLoader read(Path file) throws IOException {
        return new TrustedIssuerLoader(
                YamlConfigurationLoader.read(file, ConfigurationDocumentType.TRUSTED_ISSUERS),
                file);
    }

    List<TrustedIssuer> loadIssuers() throws IOException, GeneralSecurityException {
        var issuers = new ArrayList<TrustedIssuer>();
        Path base = file.toAbsolutePath().normalize().getParent();
        for (var entry : document.properties()) {
            var value = entry.getValue();
            var algorithm =
                    value.has("algorithm")
                            ? SignatureAlgorithm.valueOf(value.get("algorithm").asString())
                            : SignatureAlgorithm.ED25519;
            var verifiers = new ArrayList<TicketVerifier>();
            Iterable<JsonNode> paths =
                    value.has("keys") ? value.get("keys") : List.of(value.get("key"));
            for (var key : paths) {
                Path path = base.resolve(key.asString());
                verifiers.add(
                        switch (algorithm) {
                            case ED25519 -> Ed25519.loadVerifier(path);
                            case HMAC_SHA256 -> HmacSha256.load(path);
                        });
            }
            issuers.add(
                    new TrustedIssuer(
                            entry.getKey(),
                            verifiers,
                            !value.has("enabled") || value.get("enabled").asBoolean(),
                            ConfigurationSectionConverter.trustPolicy(value)));
        }
        return List.copyOf(issuers);
    }
}
