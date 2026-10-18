/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketConfiguration.java
 * sync: Load this business's section without duplicating schema rules or opening runtime resources.
 * @endcontext
 */
package me.pectics.chorus.core.ticket.config;

import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.config.ConfigurationValueParser;
import me.pectics.chorus.core.ticket.TicketCompression;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import tools.jackson.databind.JsonNode;

/**
 * Stateless loading of the schema-approved ticket section into its business-owned configuration.
 */
public final class TicketConfigurationLoader
        implements ConfigurationSectionLoader<TicketConfiguration> {
    @Override
    public TicketConfiguration load(JsonNode value, Path base) {
        return new TicketConfiguration(
                value.get("cookie-key").asString(),
                value.get("max-payload-bytes").asInt(),
                value.get("max-property-bytes").asInt(),
                value.has("compression")
                        ? TicketCompression.valueOf(value.get("compression").asString())
                        : TicketCompression.ZSTD,
                ConfigurationValueParser.duration(value.get("max-lifetime")));
    }
}
