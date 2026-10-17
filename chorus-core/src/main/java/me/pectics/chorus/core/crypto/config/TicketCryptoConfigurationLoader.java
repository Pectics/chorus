/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/crypto/TicketCryptoConfiguration.java
 * sync: Load this business's section without duplicating schema rules or opening runtime resources.
 * @endcontext
 */
package me.pectics.chorus.core.crypto.config;

import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.config.ConfigurationValueParser;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketCryptoConfiguration;
import tools.jackson.databind.JsonNode;

/**
 * Stateless loading of the schema-approved crypto section into its business-owned configuration.
 */
public final class TicketCryptoConfigurationLoader
        implements ConfigurationSectionLoader<TicketCryptoConfiguration> {
    @Override
    public TicketCryptoConfiguration load(JsonNode value, Path base) {
        return switch (SignatureAlgorithm.valueOf(value.get("algorithm").asString())) {
            case ED25519 ->
                    new TicketCryptoConfiguration.Ed25519(
                            ConfigurationValueParser.optionalPath(value, "private-key", base),
                            ConfigurationValueParser.optionalPath(value, "public-key", base));
            case HMAC_SHA256 ->
                    new TicketCryptoConfiguration.HmacSha256(
                            ConfigurationValueParser.optionalPath(value, "key", base));
        };
    }
}
