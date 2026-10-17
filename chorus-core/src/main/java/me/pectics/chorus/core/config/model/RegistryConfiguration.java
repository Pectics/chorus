/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.9
 * sync: Keep directory source settings separate from the loaded routing and trust snapshots.
 * @endcontext
 */
package me.pectics.chorus.core.config.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Directory source selection and file locations. File loading resolves paths relative to the main
 * configuration; construction performs no I/O. Null components throw NullPointerException.
 */
public record RegistryConfiguration(Provider provider, Config config) {
    public enum Provider {
        CONFIG
    }

    public RegistryConfiguration {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(config, "config");
    }

    /** Locations of the independently validated node and issuer documents. */
    public record Config(Path serversPath, Path issuersPath) {
        public Config {
            Objects.requireNonNull(serversPath, "serversPath");
            Objects.requireNonNull(issuersPath, "issuersPath");
        }
    }
}
