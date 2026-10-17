/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketIssuanceConfiguration.java
 * sync: Load this business's section without duplicating schema rules or opening runtime resources.
 * @endcontext
 */
package me.pectics.chorus.core.ticket.config;

import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.config.ConfigurationValueParser;
import me.pectics.chorus.core.ticket.TicketIssuanceConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuancePolicy;
import me.pectics.chorus.core.trust.config.IssuerTrustPolicyConfigurationLoader;
import tools.jackson.databind.JsonNode;

/**
 * Stateless loading of the schema-approved issuance section into its business-owned configuration.
 */
public final class TicketIssuanceConfigurationLoader
        implements ConfigurationSectionLoader<TicketIssuanceConfiguration> {
    @Override
    public TicketIssuanceConfiguration load(JsonNode value, Path base) {
        return new TicketIssuanceConfiguration(
                ConfigurationValueParser.duration(value.get("ttl")),
                new TicketIssuancePolicy(
                        new IssuerTrustPolicyConfigurationLoader().load(value, base),
                        ConfigurationValueParser.strings(value.get("allowed-targets"))));
    }
}
