/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuardConfiguration.java
 * sync: Load this business's section without duplicating schema rules or opening runtime resources.
 * @endcontext
 */
package me.pectics.chorus.core.replay.config;

import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.config.ConfigurationValueParser;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import tools.jackson.databind.JsonNode;

/**
 * Stateless loading of the schema-approved replay-guard section into its business-owned
 * configuration.
 */
public final class ReplayGuardConfigurationLoader
        implements ConfigurationSectionLoader<ReplayGuardConfiguration> {
    @Override
    public ReplayGuardConfiguration load(JsonNode value, Path base) {
        var backend = ReplayGuardConfiguration.Backend.valueOf(value.get("backend").asString());
        return switch (backend) {
            case LOCAL ->
                    new ReplayGuardConfiguration(backend, local(value.get("local"), base), null);
            case REGISTRY -> new ReplayGuardConfiguration(backend, null, null);
            case REDIS ->
                    new ReplayGuardConfiguration(backend, null, redis(value.get("redis"), base));
        };
    }

    private static ReplayGuardConfiguration.Local local(JsonNode value, Path base) {
        return new ReplayGuardConfiguration.Local(
                base.resolve(value.get("path").asString()),
                ConfigurationValueParser.duration(value.get("busy-timeout")));
    }

    private static ReplayGuardConfiguration.Redis redis(JsonNode value, Path base) {
        var tls = value.get("tls");
        var timeouts = value.get("timeouts");
        return new ReplayGuardConfiguration.Redis(
                value.get("host").asString(),
                value.get("port").asInt(),
                value.get("database").asInt(),
                value.get("username").asString(),
                base.resolve(value.get("password-path").asString()),
                new ReplayGuardConfiguration.Tls(
                        ReplayGuardConfiguration.TlsMode.valueOf(tls.get("mode").asString()),
                        tls.has("ca-cert") ? base.resolve(tls.get("ca-cert").asString()) : null),
                new ReplayGuardConfiguration.Timeouts(
                        ConfigurationValueParser.duration(timeouts.get("connect")),
                        ConfigurationValueParser.duration(timeouts.get("command")),
                        ConfigurationValueParser.duration(timeouts.get("acquire"))),
                new ReplayGuardConfiguration.Pool(value.get("pool").get("size").asInt()));
    }
}
