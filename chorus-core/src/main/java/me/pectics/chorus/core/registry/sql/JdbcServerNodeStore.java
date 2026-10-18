/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3, 3.9, and 7.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/sql/JdbcSchemaInitializer.java
 * sync: Keep fixed table names and schema version aligned with the initializer.
 * @endcontext
 */
package me.pectics.chorus.core.registry.sql;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.registry.RegistryConfiguration.Engine;
import me.pectics.chorus.core.registry.RegistryConfiguration.Sql;

/**
 * Pooled JDBC reads of versioned server node records. Opening loads credentials and may block; load
 * and close must run off event loops. The schema is created by {@link JdbcSchemaInitializer} before
 * this store opens. Never creates tables or loads issuer keys.
 */
public final class JdbcServerNodeStore implements SqlServerNodeRegistry.ServerNodeStore {
    private final JdbcPool pool;
    private final Engine engine;
    private final int querySeconds;

    private JdbcServerNodeStore(JdbcPool pool, Sql settings) {
        this.pool = pool;
        this.engine = settings.engine();
        this.querySeconds = seconds(settings.timeouts().query());
    }

    public static JdbcServerNodeStore open(Sql settings) throws IOException {
        return new JdbcServerNodeStore(JdbcPool.open(settings, true), settings);
    }

    @Override
    public Map<String, ServerNode> load() throws SQLException {
        String prefix = engine == Engine.POSTGRESQL ? "public." : "";
        try (var connection = pool.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String expected =
                    switch (engine) {
                        case POSTGRESQL -> "PostgreSQL";
                        case MYSQL -> "MySQL";
                        case MARIADB -> "MariaDB";
                    };
            if (!expected.equals(product)) throw new SQLException("SQL engine mismatch");
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(querySeconds);
                try (var version =
                        statement.executeQuery(
                                "SELECT version FROM "
                                        + prefix
                                        + "chorus_schema WHERE singleton = 1")) {
                    if (!version.next() || version.getInt(1) != 1 || version.next()) {
                        throw new SQLException("Unsupported SQL registry schema version");
                    }
                }
                var nodes = new HashMap<String, ServerNode>();
                try (var rows =
                        statement.executeQuery(
                                "SELECT node_id, display_name, host, port FROM "
                                        + prefix
                                        + "chorus_servers")) {
                    while (rows.next()) {
                        var node =
                                new ServerNode(
                                        rows.getString(1),
                                        rows.getString(2),
                                        rows.getString(3),
                                        rows.getInt(4));
                        if (nodes.putIfAbsent(node.nodeId(), node) != null) {
                            throw new SQLException("Duplicate SQL node ID");
                        }
                    }
                }
                connection.commit();
                return Map.copyOf(nodes);
            }
        } catch (SQLException failure) {
            throw new SQLException(
                    "Unable to read SQL server node registry; check connection, schema version, and rows",
                    failure.getSQLState(),
                    failure.getErrorCode());
        }
    }

    private static int seconds(java.time.Duration duration) {
        return Math.toIntExact((duration.toMillis() + 999) / 1000);
    }

    @Override
    public void close() {
        pool.close();
    }
}
