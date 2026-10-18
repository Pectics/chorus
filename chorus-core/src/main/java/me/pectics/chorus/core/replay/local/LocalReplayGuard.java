/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.8, and 5.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuard.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/replay/local/SqliteReplayStore.java
 * sync: Complete results after storage returns; drain accepted work before closing the store.
 * @endcontext
 */
package me.pectics.chorus.core.replay.local;

import java.io.IOException;
import java.nio.file.Path;
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
import me.pectics.chorus.core.ticket.TicketTiming;

/**
 * One persistent receiver database, one bounded serial I/O worker. Opening and closing may block;
 * consume never performs I/O on the caller. LOCAL does not pre-register tickets. Completion
 * callbacks may run on the I/O worker and must not block it. Preserve the database and its WAL
 * across restarts; a new empty path is new state.
 */
public final class LocalReplayGuard implements ReplayGuard {
    private static final int QUEUE_CAPACITY = 1024;

    private final Object lifecycle = new Object();
    private final SqliteReplayStore store;
    private final ThreadPoolExecutor executor;
    private final String targetNodeId;
    private volatile boolean closed;
    private volatile Thread worker;
    private volatile SQLException closeFailure;

    public LocalReplayGuard(Path file, String targetNodeId, Duration busyTimeout, Clock clock)
            throws SQLException, IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(clock, "clock");
        if (targetNodeId == null
                || !targetNodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || busyTimeout.isNegative()
                || busyTimeout.toMillis() < 1
                || busyTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid local replay configuration");
        }
        this.targetNodeId = targetNodeId;
        this.store = new SqliteReplayStore(file, targetNodeId, clock);
        try {
            this.executor =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                            task -> {
                                var thread = new Thread(task, "chorus-local-replay");
                                thread.setDaemon(true);
                                worker = thread;
                                return thread;
                            }) {
                        @Override
                        protected void terminated() {
                            try {
                                store.close();
                            } catch (SQLException e) {
                                closeFailure = e;
                            }
                        }
                    };
        } catch (RuntimeException | Error failure) {
            try {
                store.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    @Override
    public CompletionStage<ReplayDecision> consume(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt) {
        if (issuerNodeId == null || issuerNodeId.isBlank() || jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("Issuer and ticket IDs are required");
        }
        long retainUntil =
                Math.addExact(expiresAt.toEpochMilli(), TicketTiming.CLOCK_SKEW.toMillis());
        var result = new CompletableFuture<ReplayDecision>();
        synchronized (lifecycle) {
            if (closed || store.isBroken() || !this.targetNodeId.equals(targetNodeId)) {
                return CompletableFuture.completedFuture(ReplayDecision.UNAVAILABLE);
            }
            try {
                executor.execute(
                        () -> {
                            try {
                                result.complete(store.consume(issuerNodeId, jti, retainUntil));
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

    /**
     * Drains accepted work before closing storage. Calls from a completion callback on the worker
     * initiate shutdown and return; an external call can wait for termination and report close
     * errors.
     */
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
                // Accepted work is bounded and storage lock waits have a configured timeout.
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
