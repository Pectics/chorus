/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3 and 7.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/sql/JdbcPool.java
 * sync: Create the current schema idempotently and never migrate or recreate an unsupported version.
 * @endcontext
 */
package me.pectics.chorus.core.registry.sql;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import me.pectics.chorus.core.registry.RegistryConfiguration.Engine;
import me.pectics.chorus.core.registry.RegistryConfiguration.Sql;

/**
 * Idempotent creation of the versioned registry and replay tables. Runs once during blocking
 * startup with the configured read-write account before any consumer opens a pool. PostgreSQL
 * initializers share a transaction-scoped advisory lock before creating any catalog objects.
 */
public final class JdbcSchemaInitializer {
    private static final int LOCK_NAMESPACE = 0x43485253; // CHRS
    private static final int SCHEMA_LOCK = 0x5343484D; // SCHM

    private JdbcSchemaInitializer() {}

    public static void initialize(Sql settings) throws IOException, SQLException {
        try (var pool = JdbcPool.open(settings, false);
                var connection = pool.getConnection()) {
            int querySeconds =
                    Math.toIntExact((settings.timeouts().query().toMillis() + 999) / 1000);
            boolean transactional = settings.engine() == Engine.POSTGRESQL;
            if (transactional) {
                connection.setAutoCommit(false);
            }
            try {
                if (transactional) {
                    lockPostgresqlSchema(connection, querySeconds);
                }
                createTables(connection, settings.engine(), querySeconds);
                ensureVersion(connection, settings.engine(), querySeconds);
                if (transactional) {
                    connection.commit();
                }
            } catch (SQLException failure) {
                if (transactional) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                throw new SQLException(
                        "Unable to initialize SQL schema",
                        failure.getSQLState(),
                        failure.getErrorCode());
            }
        }
    }

    private static void lockPostgresqlSchema(Connection connection, int querySeconds)
            throws SQLException {
        try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
            lock.setQueryTimeout(querySeconds);
            lock.setInt(1, LOCK_NAMESPACE);
            lock.setInt(2, SCHEMA_LOCK);
            lock.execute();
        }
    }

    private static void createTables(Connection connection, Engine engine, int querySeconds)
            throws SQLException {
        for (String statement : statements(engine)) {
            try (var create = connection.createStatement()) {
                create.setQueryTimeout(querySeconds);
                create.execute(statement);
            }
        }
    }

    private static void ensureVersion(Connection connection, Engine engine, int querySeconds)
            throws SQLException {
        String prefix = prefix(engine);
        try (var insert = connection.createStatement()) {
            insert.setQueryTimeout(querySeconds);
            insert.execute(
                    engine == Engine.POSTGRESQL
                            ? "INSERT INTO "
                                    + prefix
                                    + "chorus_schema (singleton, version) VALUES (1, 1) "
                                    + "ON CONFLICT DO NOTHING"
                            : "INSERT IGNORE INTO "
                                    + prefix
                                    + "chorus_schema (singleton, version) VALUES (1, 1)");
        }
        try (var query = connection.createStatement()) {
            query.setQueryTimeout(querySeconds);
            try (var result =
                    query.executeQuery(
                            "SELECT version FROM "
                                    + prefix
                                    + "chorus_schema WHERE singleton = 1")) {
                if (!result.next() || result.getInt(1) != 1 || result.next()) {
                    throw new SQLException("Unsupported SQL schema version");
                }
            }
        }
    }

    private static String prefix(Engine engine) {
        return engine == Engine.POSTGRESQL ? "public." : "";
    }

    private static String[] statements(Engine engine) {
        if (engine == Engine.POSTGRESQL) {
            return new String[] {
                """
                CREATE TABLE IF NOT EXISTS public.chorus_schema (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    version INTEGER NOT NULL CHECK (version = 1)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS public.chorus_servers (
                    node_id VARCHAR(128) COLLATE "C" PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    host TEXT NOT NULL,
                    port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS public.chorus_replay_registrations (
                    issuer_node_id VARCHAR(128) COLLATE "C" NOT NULL,
                    jti VARCHAR(128) COLLATE "C" NOT NULL,
                    target_node_id VARCHAR(128) COLLATE "C" NOT NULL,
                    retain_until_ms BIGINT NOT NULL,
                    PRIMARY KEY (issuer_node_id, jti)
                )
                """,
                """
                CREATE INDEX IF NOT EXISTS chorus_replay_expiry
                    ON public.chorus_replay_registrations (retain_until_ms)
                """
            };
        }
        return new String[] {
            """
            CREATE TABLE IF NOT EXISTS chorus_schema (
                singleton INTEGER PRIMARY KEY,
                version INTEGER NOT NULL,
                CHECK (singleton = 1),
                CHECK (version = 1)
            ) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS chorus_servers (
                node_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
                display_name TEXT NOT NULL,
                host TEXT NOT NULL,
                port INTEGER NOT NULL CHECK (port BETWEEN 1 AND 65535)
            ) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4
            """,
            """
            CREATE TABLE IF NOT EXISTS chorus_replay_registrations (
                issuer_node_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                jti VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                target_node_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                retain_until_ms BIGINT NOT NULL,
                PRIMARY KEY (issuer_node_id, jti),
                INDEX chorus_replay_expiry (retain_until_ms)
            ) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4
            """
        };
    }
}
