/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 5.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/local/LocalReplayGuard.java
 * sync: Preserve the state schema and FULL-synchronous WAL commit before accepting consumption.
 * @endcontext
 */
package me.pectics.chorus.core.replay.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import me.pectics.chorus.core.replay.ReplayDecision;

/**
 * Synchronous storage owned by one guard. Startup and termination may use the caller's thread; the
 * guard serializes consumption and closes only after all accepted work ends.
 */
final class SqliteReplayStore implements AutoCloseable {
    private final Connection connection;
    private final int capacity;
    private final Clock clock;
    private volatile boolean broken;

    SqliteReplayStore(
            Path file,
            String targetNodeId,
            long skewMillis,
            int capacity,
            int busyMillis,
            Clock clock)
            throws SQLException, IOException {
        this.capacity = capacity;
        this.clock = clock;
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(
                Objects.requireNonNull(
                        absolute.getParent(), "Replay database must have a parent directory"));
        this.connection =
                DriverManager.getConnection("jdbc:sqlite:" + absolute.toUri() + "?mode=rwc");
        try {
            initialize(targetNodeId, skewMillis, busyMillis);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private void initialize(String targetNodeId, long skewMillis, int busyMillis)
            throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + busyMillis);
            try (var result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!result.next() || !result.getString(1).equalsIgnoreCase("wal")) {
                    throw new SQLException("Local replay requires WAL mode");
                }
            }
            statement.execute("PRAGMA synchronous=FULL");
            try (var result = statement.executeQuery("PRAGMA synchronous")) {
                if (!result.next() || result.getInt(1) != 2) {
                    throw new SQLException("Local replay requires FULL synchronization");
                }
            }
            statement.execute("BEGIN IMMEDIATE");
            int version;
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                version = result.getInt(1);
            }
            if (version == 0) {
                try (var result =
                        statement.executeQuery(
                                "SELECT 1 FROM sqlite_schema WHERE type='table' LIMIT 1")) {
                    if (result.next()) {
                        throw new SQLException(
                                "Refusing to initialize an unknown nonempty database");
                    }
                }
                statement.execute(
                        """
                        CREATE TABLE chorus_replay_meta (
                            id INTEGER PRIMARY KEY CHECK (id = 1),
                            node_id TEXT NOT NULL,
                            skew_ms INTEGER NOT NULL
                        )
                        """);
                statement.execute(
                        """
                        CREATE TABLE chorus_consumed_tickets (
                            issuer_node_id TEXT NOT NULL,
                            jti TEXT NOT NULL,
                            retain_until_ms INTEGER NOT NULL,
                            PRIMARY KEY (issuer_node_id, jti)
                        )
                        """);
                statement.execute(
                        "CREATE INDEX chorus_consumed_expiry "
                                + "ON chorus_consumed_tickets(retain_until_ms)");
                try (var insert =
                        connection.prepareStatement(
                                "INSERT INTO chorus_replay_meta VALUES (1, ?, ?)")) {
                    insert.setString(1, targetNodeId);
                    insert.setLong(2, skewMillis);
                    insert.executeUpdate();
                }
                statement.execute("PRAGMA user_version=1");
            } else if (version != 1) {
                throw new SQLException("Unsupported local replay schema");
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT issuer_node_id, jti, retain_until_ms FROM chorus_consumed_tickets LIMIT 0")) {
                // Existing state must validate; missing tables must never be recreated as empty
                // ledgers.
                result.getMetaData();
            }
            try (var result =
                    statement.executeQuery(
                            "SELECT node_id, skew_ms FROM chorus_replay_meta WHERE id=1")) {
                if (!result.next()
                        || !targetNodeId.equals(result.getString(1))
                        || result.getLong(2) != skewMillis) {
                    throw new SQLException(
                            "Replay state belongs to a different node or clock tolerance");
                }
            }
            statement.execute("COMMIT");
        }
    }

    boolean isBroken() {
        return broken;
    }

    ReplayDecision consume(String issuer, String jti, long retainUntil) {
        if (broken || retainUntil <= clock.millis()) {
            return ReplayDecision.UNAVAILABLE;
        }
        boolean begun = false;
        try (var transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            begun = true;
            try (var cleanup =
                    connection.prepareStatement(
                            "DELETE FROM chorus_consumed_tickets WHERE retain_until_ms <= ?")) {
                cleanup.setLong(1, clock.millis());
                cleanup.executeUpdate();
            }
            try (var find =
                    connection.prepareStatement(
                            "SELECT 1 FROM chorus_consumed_tickets WHERE issuer_node_id=? AND jti=?")) {
                find.setString(1, issuer);
                find.setString(2, jti);
                try (var result = find.executeQuery()) {
                    if (result.next()) {
                        transaction.execute("COMMIT");
                        return ReplayDecision.REPLAYED;
                    }
                }
            }
            try (var count =
                    transaction.executeQuery("SELECT COUNT(*) FROM chorus_consumed_tickets")) {
                if (count.next() && count.getLong(1) >= capacity) {
                    transaction.execute("ROLLBACK");
                    return ReplayDecision.CAPACITY_EXCEEDED;
                }
            }
            try (var insert =
                    connection.prepareStatement(
                            "INSERT INTO chorus_consumed_tickets VALUES (?, ?, ?)")) {
                insert.setString(1, issuer);
                insert.setString(2, jti);
                insert.setLong(3, retainUntil);
                insert.executeUpdate();
            }
            transaction.execute("COMMIT");
            return ReplayDecision.ACCEPTED;
        } catch (SQLException e) {
            if (begun) {
                try (var rollback = connection.createStatement()) {
                    rollback.execute("ROLLBACK");
                } catch (SQLException unknownState) {
                    broken = true;
                }
            }
            return ReplayDecision.UNAVAILABLE;
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
