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
 * startup with the configured read-write account before any consumer opens a pool.
 */
public final class JdbcSchemaInitializer {
    private JdbcSchemaInitializer() {}

    public static void initialize(Sql settings) throws IOException, SQLException {
        try (var pool = JdbcPool.open(settings, false);
                var connection = pool.getConnection()) {
            boolean transactional = settings.engine() == Engine.POSTGRESQL;
            if (transactional) {
                connection.setAutoCommit(false);
            }
            try {
                createTables(connection, settings.engine());
                ensureVersion(connection, settings.engine());
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

    private static void createTables(Connection connection, Engine engine) throws SQLException {
        for (String statement : statements(engine)) {
            try (var create = connection.createStatement()) {
                create.execute(statement);
            }
        }
    }

    private static void ensureVersion(Connection connection, Engine engine) throws SQLException {
        String prefix = prefix(engine);
        try (var insert = connection.createStatement()) {
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
        try (var query = connection.createStatement();
                var result =
                        query.executeQuery(
                                "SELECT version FROM "
                                        + prefix
                                        + "chorus_schema WHERE singleton = 1")) {
            if (!result.next() || result.getInt(1) != 1 || result.next()) {
                throw new SQLException("Unsupported SQL schema version");
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
