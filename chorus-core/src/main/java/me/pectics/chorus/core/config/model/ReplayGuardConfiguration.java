/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.9, and 7.2
 * sync: Keep replay storage settings separate from verification's shared clock skew; reject reserved backends at startup.
 * @endcontext
 */
package me.pectics.chorus.core.config.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Replay backend selection. LOCAL requires local settings; reserved backends are rejected during
 * startup. Null required components throw NullPointerException. Construction performs no I/O.
 */
public record ReplayGuardConfiguration(Backend backend, Local local) {
    public enum Backend {
        LOCAL,
        // TODO: Implement durable consumption using the selected registry database.
        REGISTRY,
        // TODO: Define Redis settings and implement a durable Redis ReplayGuard.
        REDIS
    }

    public ReplayGuardConfiguration {
        Objects.requireNonNull(backend, "backend");
        if (backend == Backend.LOCAL) {
            Objects.requireNonNull(local, "local");
        }
    }

    /**
     * Persistent SQLite settings. File loading resolves the path relative to the main
     * configuration. Capacity must be positive and the positive lock wait must fit 1..2147483647
     * whole milliseconds. Invalid limits throw IllegalArgumentException or ArithmeticException for
     * duration overflow; null path or timeout throws NullPointerException. No operational defaults
     * are implied.
     */
    public record Local(Path path, int maxEntries, Duration busyTimeout) {
        public Local {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(busyTimeout, "busyTimeout");
            if (maxEntries < 1
                    || busyTimeout.isNegative()
                    || busyTimeout.toMillis() < 1
                    || busyTimeout.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid local replay configuration");
            }
        }
    }
}
