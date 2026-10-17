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
        requireImplementedBackend(backend);
        var local = value.get("local");
        return new ReplayGuardConfiguration(
                backend,
                new ReplayGuardConfiguration.Local(
                        base.resolve(local.get("path").asString()),
                        local.get("max-entries").asInt(),
                        ConfigurationValueParser.duration(local.get("busy-timeout"))));
    }

    /** Rejects reserved backend options before resource assembly; null means no replay section. */
    public static void requireImplementedOptions(JsonNode value) {
        if (value != null) {
            requireImplementedBackend(
                    ReplayGuardConfiguration.Backend.valueOf(value.get("backend").asString()));
        }
    }

    private static void requireImplementedBackend(ReplayGuardConfiguration.Backend backend) {
        switch (backend) {
            case LOCAL -> {}
            case REGISTRY -> {
                // TODO: Reuse the configured registry database for durable ticket consumption.
                throw new IllegalArgumentException(
                        "replay-guard.backend REGISTRY is not implemented");
            }
            case REDIS -> {
                // TODO: Define Redis settings and implement a durable Redis ReplayGuard.
                throw new IllegalArgumentException("replay-guard.backend REDIS is not implemented");
            }
        }
    }
}
