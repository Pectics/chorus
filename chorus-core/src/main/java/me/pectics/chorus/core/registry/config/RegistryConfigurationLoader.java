/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/RegistryConfiguration.java
 * sync: Load this business's section without duplicating schema rules or opening runtime resources.
 * @endcontext
 */
package me.pectics.chorus.core.registry.config;

import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.config.ConfigurationValueParser;
import me.pectics.chorus.core.registry.RegistryConfiguration;
import tools.jackson.databind.JsonNode;

/**
 * Stateless loading of the schema-approved registry section into its business-owned configuration.
 */
public final class RegistryConfigurationLoader
        implements ConfigurationSectionLoader<RegistryConfiguration> {
    @Override
    public RegistryConfiguration load(JsonNode value, Path base) {
        var config = value.get("config");
        return new RegistryConfiguration(
                RegistryConfiguration.Provider.valueOf(value.get("provider").asString()),
                config == null
                        ? null
                        : new RegistryConfiguration.Config(
                                base.resolve(config.get("servers-path").asString())),
                value.has("sql") ? sql(value.get("sql"), base) : null,
                value.has("cache")
                        ? new RegistryConfiguration.Cache(
                                ConfigurationValueParser.duration(
                                        value.at("/cache/refresh-interval")),
                                ConfigurationValueParser.duration(value.at("/cache/max-stale")))
                        : null);
    }

    private static RegistryConfiguration.Sql sql(JsonNode value, Path base) {
        return new RegistryConfiguration.Sql(
                RegistryConfiguration.Engine.valueOf(value.get("engine").asString()),
                value.get("host").asString(),
                value.get("port").asInt(),
                value.get("database").asString(),
                value.get("username").asString(),
                base.resolve(value.get("password-path").asString()),
                new RegistryConfiguration.Tls(
                        RegistryConfiguration.TlsMode.valueOf(value.at("/tls/mode").asString()),
                        ConfigurationValueParser.optionalPath(value.get("tls"), "ca-cert", base)),
                new RegistryConfiguration.Pool(
                        value.at("/pool/max-connections").asInt(),
                        ConfigurationValueParser.duration(value.at("/pool/acquire-timeout"))),
                new RegistryConfiguration.Timeouts(
                        ConfigurationValueParser.duration(value.at("/timeouts/connect")),
                        ConfigurationValueParser.duration(value.at("/timeouts/query")),
                        ConfigurationValueParser.duration(value.at("/timeouts/socket"))));
    }
}
