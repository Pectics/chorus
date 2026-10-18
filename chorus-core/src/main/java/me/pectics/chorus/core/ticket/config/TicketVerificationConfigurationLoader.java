/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketVerificationConfiguration.java
 * sync: Load this business's section without duplicating schema rules or opening runtime resources.
 * @endcontext
 */
package me.pectics.chorus.core.ticket.config;

import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.identity.BuiltinIdentityResolutionPolicy;
import me.pectics.chorus.core.ticket.TicketVerificationConfiguration;
import tools.jackson.databind.JsonNode;

/**
 * Stateless loading of the schema-approved verification section into its business-owned
 * configuration.
 */
public final class TicketVerificationConfigurationLoader
        implements ConfigurationSectionLoader<TicketVerificationConfiguration> {
    @Override
    public TicketVerificationConfiguration load(JsonNode value, Path base) {
        return new TicketVerificationConfiguration(
                value.get("enforce-transfer-intent").asBoolean(),
                BuiltinIdentityResolutionPolicy.valueOf(value.get("identity-policy").asString()),
                base.resolve(value.get("trusted-issuers-path").asString()));
    }
}
