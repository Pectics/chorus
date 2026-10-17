/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/node/ServerNodeConfiguration.java
 * sync: Load this business's section without duplicating schema rules or opening runtime resources.
 * @endcontext
 */
package me.pectics.chorus.core.node.config;

import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.node.ServerNodeConfiguration;
import me.pectics.chorus.core.node.ServerNodeRole;
import tools.jackson.databind.JsonNode;

/** Stateless loading of the schema-approved node section into its business-owned configuration. */
public final class ServerNodeConfigurationLoader
        implements ConfigurationSectionLoader<ServerNodeConfiguration> {
    @Override
    public ServerNodeConfiguration load(JsonNode value, Path base) {
        return new ServerNodeConfiguration(
                value.get("id").asString(),
                value.has("display-name") ? value.get("display-name").asString() : null,
                ServerNodeRole.valueOf(value.get("role").asString()));
    }
}
