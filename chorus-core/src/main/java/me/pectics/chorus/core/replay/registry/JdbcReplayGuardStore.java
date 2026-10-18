/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 7.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/registry/RegistryReplayGuard.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/registry/sql/JdbcSchemaInitializer.java
 * sync: Registration inserts one row; consumption deletes it atomically and reports the row count.
 * @endcontext
 */
package me.pectics.chorus.core.replay.registry;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import me.pectics.chorus.core.registry.RegistryConfiguration.Engine;
import me.pectics.chorus.core.registry.RegistryConfiguration.Sql;
import me.pectics.chorus.core.registry.sql.JdbcPool;
import me.pectics.chorus.core.replay.ReplayDecision;

/**
 * Blocking registry-database storage owned by one guard. The schema is created by the SQL
 * initializer before this store opens. Driver failures are never propagated with their diagnostics.
 */
final class JdbcReplayGuardStore implements RegistryReplayGuardStore {
    private final JdbcPool pool;
    private final String prefix;
    private final int querySeconds;

    JdbcReplayGuardStore(Sql settings) throws IOException, SQLException {
        this.pool = JdbcPool.open(settings, false);
        this.prefix = settings.engine() == Engine.POSTGRESQL ? "public." : "";
        this.querySeconds = seconds(settings.timeouts().query());
        try {
            validate();
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                pool.close();
            } catch (RuntimeException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private void validate() throws SQLException {
        try (var connection = pool.getConnection();
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(querySeconds);
            try (var version =
                    statement.executeQuery(
                            "SELECT version FROM "
                                    + prefix
                                    + "chorus_schema WHERE singleton = 1")) {
                if (!version.next() || version.getInt(1) != 1 || version.next()) {
                    throw new SQLException("Unsupported SQL schema version");
                }
            }
            try (var columns =
                    statement.executeQuery(
                            "SELECT issuer_node_id, jti, target_node_id, retain_until_ms FROM "
                                    + prefix
                                    + "chorus_replay_registrations LIMIT 0")) {
                columns.getMetaData();
            }
        }
    }

    @Override
    public void register(
            String issuerNodeId,
            String jti,
            String targetNodeId,
            long retainUntilMillis,
            long nowMillis)
            throws SQLException {
        try (var connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                cleanup(connection, nowMillis);
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO "
                                        + prefix
                                        + "chorus_replay_registrations "
                                        + "(issuer_node_id, jti, target_node_id, retain_until_ms) "
                                        + "VALUES (?, ?, ?, ?)")) {
                    insert.setQueryTimeout(querySeconds);
                    insert.setString(1, issuerNodeId);
                    insert.setString(2, jti);
                    insert.setString(3, targetNodeId);
                    insert.setLong(4, retainUntilMillis);
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                rollback(connection, failure);
                throw sanitized("Unable to register replay ticket", failure);
            }
        }
    }

    @Override
    public ReplayDecision consume(
            String issuerNodeId, String jti, long retainUntilMillis, long nowMillis) {
        if (retainUntilMillis <= nowMillis) {
            return ReplayDecision.UNAVAILABLE;
        }
        try (var connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                cleanup(connection, nowMillis);
                int removed;
                try (var delete =
                        connection.prepareStatement(
                                "DELETE FROM "
                                        + prefix
                                        + "chorus_replay_registrations "
                                        + "WHERE issuer_node_id = ? AND jti = ? "
                                        + "AND retain_until_ms > ?")) {
                    delete.setQueryTimeout(querySeconds);
                    delete.setString(1, issuerNodeId);
                    delete.setString(2, jti);
                    delete.setLong(3, nowMillis);
                    removed = delete.executeUpdate();
                }
                connection.commit();
                return removed == 1 ? ReplayDecision.ACCEPTED : ReplayDecision.REPLAYED;
            } catch (SQLException failure) {
                rollback(connection, failure);
                return ReplayDecision.UNAVAILABLE;
            }
        } catch (SQLException failure) {
            return ReplayDecision.UNAVAILABLE;
        }
    }

    private void cleanup(Connection connection, long nowMillis) throws SQLException {
        try (var delete =
                connection.prepareStatement(
                        "DELETE FROM "
                                + prefix
                                + "chorus_replay_registrations "
                                + "WHERE retain_until_ms <= ?")) {
            delete.setQueryTimeout(querySeconds);
            delete.setLong(1, nowMillis);
            delete.executeUpdate();
        }
    }

    private static void rollback(Connection connection, SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            failure.addSuppressed(rollback);
        }
    }

    private static SQLException sanitized(String message, SQLException failure) {
        return new SQLException(
                message + "; check connection and schema",
                failure.getSQLState(),
                failure.getErrorCode());
    }

    private static int seconds(Duration duration) {
        return Math.toIntExact((duration.toMillis() + 999) / 1000);
    }

    @Override
    public void close() {
        pool.close();
    }
}
