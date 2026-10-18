/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.9, and 7.2
 * sync: Keep backend selection, shared storage settings, and the fixed replay tolerance explicit.
 * @endcontext
 */
package me.pectics.chorus.core.replay;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Replay backend selection. LOCAL requires local settings, REDIS requires Redis settings, and
 * REGISTRY reuses the configured registry database. Construction performs no I/O.
 */
public record ReplayGuardConfiguration(Backend backend, Local local, Redis redis) {
    public enum Backend {
        LOCAL,
        REGISTRY,
        REDIS
    }

    public ReplayGuardConfiguration {
        Objects.requireNonNull(backend, "backend");
        switch (backend) {
            case LOCAL -> {
                Objects.requireNonNull(local, "LOCAL requires local settings");
                if (redis != null) {
                    throw new IllegalArgumentException("LOCAL cannot use Redis settings");
                }
            }
            case REDIS -> {
                Objects.requireNonNull(redis, "REDIS requires Redis settings");
                if (local != null) {
                    throw new IllegalArgumentException("REDIS cannot use local settings");
                }
            }
            case REGISTRY -> {
                if (local != null || redis != null) {
                    throw new IllegalArgumentException("REGISTRY has no additional settings");
                }
            }
        }
    }

    /**
     * Persistent SQLite settings. File loading resolves the path relative to the main
     * configuration. The positive lock wait must fit 1..2147483647 whole milliseconds.
     */
    public record Local(Path path, Duration busyTimeout) {
        public Local {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(busyTimeout, "busyTimeout");
            if (busyTimeout.toMillis() < 1 || busyTimeout.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid local replay configuration");
            }
        }
    }

    /** One standalone Redis endpoint with ACL credentials and bounded pool settings. */
    public record Redis(
            String host,
            int port,
            int database,
            String username,
            Path passwordPath,
            Tls tls,
            Timeouts timeouts,
            Pool pool) {
        public Redis {
            Objects.requireNonNull(passwordPath, "passwordPath");
            Objects.requireNonNull(tls, "tls");
            Objects.requireNonNull(timeouts, "timeouts");
            Objects.requireNonNull(pool, "pool");
            if (host == null
                    || host.isBlank()
                    || username == null
                    || username.isBlank()
                    || port < 1
                    || port > 65535
                    || database < 0) {
                throw new IllegalArgumentException("Invalid Redis endpoint");
            }
        }
    }

    public enum TlsMode {
        DISABLED,
        VERIFY_FULL
    }

    /** caCert is an optional PEM CA bundle; DISABLED cannot consume a certificate. */
    public record Tls(TlsMode mode, Path caCert) {
        public Tls {
            Objects.requireNonNull(mode, "mode");
            if (mode == TlsMode.DISABLED && caCert != null) {
                throw new IllegalArgumentException("Disabled TLS cannot specify a CA certificate");
            }
        }
    }

    /** Connect and command waits are bounded; pool acquisition waits at least 500 ms. */
    public record Timeouts(Duration connect, Duration command, Duration acquire) {
        public Timeouts {
            milliseconds(connect);
            milliseconds(command);
            if (milliseconds(acquire) < 500) {
                throw new IllegalArgumentException("Invalid Redis acquire timeout");
            }
        }
    }

    public record Pool(int size) {
        public Pool {
            if (size < 1) {
                throw new IllegalArgumentException("Invalid Redis pool size");
            }
        }
    }

    private static long milliseconds(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long value = duration.toMillis();
        if (value < 1 || value > Integer.MAX_VALUE || !Duration.ofMillis(value).equals(duration)) {
            throw new IllegalArgumentException(
                    "Redis durations require bounded whole milliseconds");
        }
        return value;
    }
}
