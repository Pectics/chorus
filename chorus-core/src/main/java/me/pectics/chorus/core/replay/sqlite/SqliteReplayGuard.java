/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.8, and 5.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuard.java
 * sync: Complete ACCEPTED only after FULL-synchronous WAL commit; never change backend on errors.
 * @endcontext
 */
package me.pectics.chorus.core.replay.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuard;

/**
 * One persistent receiver database, one bounded serial I/O worker. Opening and closing may block;
 * consume never performs I/O on the caller. Completion callbacks may run on the I/O worker and must
 * not block it. Preserve the database and its WAL across restarts; a new empty path is new state.
 */
public final class SqliteReplayGuard implements ReplayGuard {
    private final Object lifecycle = new Object();
    private final Connection connection;
    private final ThreadPoolExecutor executor;
    private final String targetNodeId;
    private final long skewMillis;
    private final int capacity;
    private final Clock clock;
    private volatile boolean closed;
    private volatile boolean broken;
    private volatile Thread worker;
    private volatile SQLException closeFailure;

    public SqliteReplayGuard(
            Path file,
            String targetNodeId,
            Duration clockSkew,
            int capacity,
            Duration busyTimeout,
            Clock clock)
            throws SQLException, IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(clock, "clock");
        if (targetNodeId == null
                || !targetNodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || capacity < 1
                || clockSkew.isNegative()
                || clockSkew.getNano() != 0
                || busyTimeout.isNegative()
                || busyTimeout.toMillis() < 1
                || busyTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid local replay configuration");
        }
        this.targetNodeId = targetNodeId;
        this.skewMillis = clockSkew.toMillis();
        this.capacity = capacity;
        this.clock = clock;
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        this.connection =
                DriverManager.getConnection("jdbc:sqlite:" + absolute.toUri() + "?mode=rwc");
        try {
            initialize((int) busyTimeout.toMillis());
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException close) {
                e.addSuppressed(close);
            }
            throw e;
        }
        this.executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(capacity),
                        task -> {
                            var thread = new Thread(task, "chorus-sqlite-replay");
                            thread.setDaemon(true);
                            worker = thread;
                            return thread;
                        }) {
                    @Override
                    protected void terminated() {
                        try {
                            connection.close();
                        } catch (SQLException e) {
                            closeFailure = e;
                        }
                    }
                };
    }

    private void initialize(int busyMillis) throws SQLException {
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

    @Override
    public CompletionStage<ReplayDecision> consume(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt) {
        if (issuerNodeId == null || issuerNodeId.isBlank() || jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("Issuer and ticket IDs are required");
        }
        long retainUntil = Math.addExact(expiresAt.toEpochMilli(), skewMillis);
        var result = new CompletableFuture<ReplayDecision>();
        synchronized (lifecycle) {
            if (closed || broken || !this.targetNodeId.equals(targetNodeId)) {
                return CompletableFuture.completedFuture(ReplayDecision.UNAVAILABLE);
            }
            try {
                executor.execute(
                        () -> {
                            try {
                                result.complete(consumeStored(issuerNodeId, jti, retainUntil));
                            } catch (RuntimeException e) {
                                result.completeExceptionally(e);
                            }
                        });
            } catch (RejectedExecutionException e) {
                result.complete(ReplayDecision.UNAVAILABLE);
            }
        }
        return result;
    }

    private ReplayDecision consumeStored(String issuer, String jti, long retainUntil) {
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
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            executor.shutdown();
        }
        if (Thread.currentThread() == worker) {
            return;
        }
        try {
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                // Accepted work is bounded and SQLite lock waits have a configured timeout.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted closing local replay state", e);
        }
        if (closeFailure != null) {
            throw new IllegalStateException("Failed to close local replay state", closeFailure);
        }
    }
}
