/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.9
 * sync: Keep directory source settings separate from the loaded routing and trust snapshots.
 * @endcontext
 */
package me.pectics.chorus.core.config.model;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Server directory source and local trust settings. Paths are relative to the main configuration;
 * construction performs no I/O. SQL synchronizes routing only, never issuer approvals or keys.
 */
public record RegistryConfiguration(Provider provider, Config config, Sql sql, Cache cache) {
    public enum Provider {
        CONFIG,
        SQL
    }

    public enum Engine {
        POSTGRESQL,
        MYSQL,
        MARIADB
    }

    public enum TlsMode {
        DISABLED,
        VERIFY_FULL
    }

    /** Retains the existing CONFIG construction contract. */
    public RegistryConfiguration(Provider provider, Config config) {
        this(provider, config, null, null);
    }

    public RegistryConfiguration {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(config, "config");
        if (provider == Provider.CONFIG) {
            Objects.requireNonNull(config.serversPath(), "CONFIG requires serversPath");
        } else {
            Objects.requireNonNull(sql, "SQL requires connection settings");
            Objects.requireNonNull(cache, "SQL requires cache settings");
        }
    }

    /** Local trust is required for both providers; serversPath may be null for SQL. */
    public record Config(Path serversPath, Path issuersPath) {
        public Config {
            Objects.requireNonNull(issuersPath, "issuersPath");
        }
    }

    /**
     * One JDBC endpoint. Credentials are loaded only during resource assembly. TODO: Define account
     * separation when management or replay writes are added (ARCHITECTURE.md section 7.2).
     */
    public record Sql(
            Engine engine,
            String host,
            int port,
            String database,
            String username,
            Path passwordPath,
            Tls tls,
            Pool pool,
            Timeouts timeouts) {
        public Sql {
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(passwordPath, "passwordPath");
            Objects.requireNonNull(tls, "tls");
            Objects.requireNonNull(pool, "pool");
            Objects.requireNonNull(timeouts, "timeouts");
            if (host == null
                    || host.isBlank()
                    || database == null
                    || database.isBlank()
                    || username == null
                    || username.isBlank()
                    || port < 1
                    || port > 65535) {
                throw new IllegalArgumentException("Invalid SQL endpoint");
            }
        }
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

    /** Acquire timeout is independent of the driver's physical connection timeout. */
    public record Pool(int maxConnections, Duration acquireTimeout) {
        public Pool {
            if (maxConnections < 1 || milliseconds(acquireTimeout) < 500) {
                throw new IllegalArgumentException("Invalid SQL pool limits");
            }
        }
    }

    /**
     * Positive whole milliseconds fitting JDBC driver integer properties; query is per statement.
     */
    public record Timeouts(Duration connect, Duration query, Duration socket) {
        public Timeouts {
            milliseconds(connect);
            milliseconds(query);
            milliseconds(socket);
        }
    }

    /** maxStale is the hard age limit measured from the last complete successful refresh. */
    public record Cache(Duration refreshInterval, Duration maxStale) {
        public Cache {
            milliseconds(refreshInterval);
            milliseconds(maxStale);
            if (maxStale.compareTo(refreshInterval) <= 0) {
                throw new IllegalArgumentException("max-stale must exceed refresh-interval");
            }
        }
    }

    private static long milliseconds(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long value = duration.toMillis();
        if (value < 1 || value > Integer.MAX_VALUE || !Duration.ofMillis(value).equals(duration)) {
            throw new IllegalArgumentException("SQL durations require bounded whole milliseconds");
        }
        return value;
    }
}
