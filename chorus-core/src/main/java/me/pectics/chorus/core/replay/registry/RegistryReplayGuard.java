/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.9, and 7.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/registry/JdbcReplayStore.java
 * sync: Register before issuance and consume atomically; reject unknown outcomes without fallback.
 * @endcontext
 */
package me.pectics.chorus.core.replay.registry;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import me.pectics.chorus.core.registry.RegistryConfiguration.Sql;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuard;
import me.pectics.chorus.core.ticket.TicketTiming;

/**
 * Shared replay backend using the registry database. Opening and closing may block; register and
 * consume never perform I/O on the caller. One bounded worker serializes durable operations and
 * drains before the write pool closes. Deadlines are checked before and after storage I/O; expired
 * work never completes successfully, even if its transaction has already committed.
 */
public final class RegistryReplayGuard implements ReplayGuard {
    private static final int QUEUE_CAPACITY = 1024;

    /** Blocking database boundary used by the guard and unit tests. */
    interface Store extends AutoCloseable {
        void register(
                String issuerNodeId,
                String jti,
                String targetNodeId,
                long retainUntilMillis,
                long nowMillis)
                throws SQLException;

        ReplayDecision consume(
                String issuerNodeId, String jti, long retainUntilMillis, long nowMillis);

        @Override
        void close() throws SQLException;
    }

    private final Object lifecycle = new Object();
    private final Store store;
    private final ThreadPoolExecutor executor;
    private final String targetNodeId;
    private final Clock clock;
    private volatile boolean closed;
    private volatile Thread worker;
    private volatile SQLException closeFailure;

    public RegistryReplayGuard(Sql settings, String targetNodeId, Clock clock)
            throws IOException, SQLException {
        this(openStore(settings, targetNodeId, clock), targetNodeId, clock, QUEUE_CAPACITY);
    }

    RegistryReplayGuard(Store store, String targetNodeId, Clock clock, int queueCapacity) {
        this.store = Objects.requireNonNull(store, "store");
        this.targetNodeId = requireTarget(targetNodeId);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("Invalid registry replay queue capacity");
        }
        ThreadPoolExecutor created;
        try {
            created =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(queueCapacity),
                            task -> {
                                var thread = new Thread(task, "chorus-registry-replay");
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
        this.executor = created;
    }

    @Override
    public CompletionStage<Void> register(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt) {
        requireIds(issuerNodeId, jti);
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        long now = clock.millis();
        long retainUntil =
                Math.addExact(expiresAt.toEpochMilli(), TicketTiming.CLOCK_SKEW.toMillis());
        if (retainUntil <= now) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Replay registration is already expired"));
        }
        var result = new CompletableFuture<Void>();
        synchronized (lifecycle) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Registry replay guard is closed"));
            }
            try {
                executor.execute(
                        () -> {
                            try {
                                long executionTime = clock.millis();
                                if (retainUntil <= executionTime) {
                                    throw new IllegalArgumentException(
                                            "Replay registration is already expired");
                                }
                                store.register(
                                        issuerNodeId,
                                        jti,
                                        targetNodeId,
                                        retainUntil,
                                        executionTime);
                                if (retainUntil <= clock.millis()) {
                                    throw new IllegalStateException(
                                            "Replay registration expired during storage I/O");
                                }
                                result.complete(null);
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            }
                        });
            } catch (RejectedExecutionException rejected) {
                result.completeExceptionally(
                        new IllegalStateException("Registry replay queue is full", rejected));
            }
        }
        return result;
    }

    @Override
    public CompletionStage<ReplayDecision> consume(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt) {
        requireIds(issuerNodeId, jti);
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!this.targetNodeId.equals(targetNodeId)) {
            return CompletableFuture.completedFuture(ReplayDecision.UNAVAILABLE);
        }
        long now = clock.millis();
        long retainUntil =
                Math.addExact(expiresAt.toEpochMilli(), TicketTiming.CLOCK_SKEW.toMillis());
        if (retainUntil <= now) {
            return CompletableFuture.completedFuture(ReplayDecision.UNAVAILABLE);
        }
        var result = new CompletableFuture<ReplayDecision>();
        synchronized (lifecycle) {
            if (closed) {
                return CompletableFuture.completedFuture(ReplayDecision.UNAVAILABLE);
            }
            try {
                executor.execute(
                        () -> {
                            try {
                                long executionTime = clock.millis();
                                if (retainUntil <= executionTime) {
                                    result.complete(ReplayDecision.UNAVAILABLE);
                                    return;
                                }
                                var decision =
                                        store.consume(
                                                issuerNodeId, jti, retainUntil, executionTime);
                                result.complete(
                                        retainUntil <= clock.millis()
                                                ? ReplayDecision.UNAVAILABLE
                                                : decision);
                            } catch (RuntimeException failure) {
                                result.completeExceptionally(failure);
                            }
                        });
            } catch (RejectedExecutionException rejected) {
                result.complete(ReplayDecision.UNAVAILABLE);
            }
        }
        return result;
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
                // Accepted work is bounded and each database call has a configured timeout.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted closing registry replay state", e);
        }
        if (closeFailure != null) {
            throw new IllegalStateException("Failed to close registry replay state", closeFailure);
        }
    }

    private static Store openStore(Sql settings, String targetNodeId, Clock clock)
            throws IOException, SQLException {
        requireTarget(targetNodeId);
        Objects.requireNonNull(clock, "clock");
        return new JdbcReplayStore(settings);
    }

    private static String requireTarget(String targetNodeId) {
        if (targetNodeId == null || !targetNodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid replay target node ID");
        }
        return targetNodeId;
    }

    private static void requireIds(String issuerNodeId, String jti) {
        if (issuerNodeId == null || issuerNodeId.isBlank() || jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("Issuer and ticket IDs are required");
        }
    }
}
