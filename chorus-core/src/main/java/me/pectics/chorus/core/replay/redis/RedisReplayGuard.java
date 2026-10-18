/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/redis/JedisReplayStore.java
 * sync: Register before issuance and consume atomically; unknown outcomes never retry or fall back.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuard;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import me.pectics.chorus.core.ticket.TicketTiming;

/**
 * Shared replay backend using standalone Redis. Opening and closing may block; register and consume
 * never perform I/O on the caller. A bounded worker pool drains before the client closes. Deadlines
 * are checked before and after storage I/O; queued registration TTLs use the execution time.
 */
public final class RedisReplayGuard implements ReplayGuard {
    private static final int QUEUE_CAPACITY = 1024;

    /** Blocking Redis boundary used by the guard and unit tests. */
    interface Store extends AutoCloseable {
        void register(String issuerNodeId, String jti, long ttlMillis);

        ReplayDecision consume(String issuerNodeId, String jti);

        @Override
        void close();
    }

    private final Object lifecycle = new Object();
    private final Store store;
    private final ThreadPoolExecutor executor;
    private final String targetNodeId;
    private final Clock clock;
    private final ThreadLocal<Boolean> worker = ThreadLocal.withInitial(() -> false);
    private volatile boolean closed;
    private volatile RuntimeException closeFailure;

    public RedisReplayGuard(
            ReplayGuardConfiguration.Redis settings, String targetNodeId, Clock clock)
            throws IOException {
        this(
                openStore(settings, targetNodeId, clock),
                targetNodeId,
                clock,
                settings.pool().size(),
                QUEUE_CAPACITY);
    }

    RedisReplayGuard(
            Store store, String targetNodeId, Clock clock, int threads, int queueCapacity) {
        this.store = Objects.requireNonNull(store, "store");
        this.targetNodeId = requireTarget(targetNodeId);
        this.clock = Objects.requireNonNull(clock, "clock");
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Invalid Redis replay worker limits");
        }
        ThreadPoolExecutor created;
        try {
            var counter = new AtomicInteger();
            created =
                    new ThreadPoolExecutor(
                            threads,
                            threads,
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(queueCapacity),
                            task -> {
                                var thread =
                                        new Thread(
                                                task,
                                                "chorus-redis-replay-" + counter.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            }) {
                        @Override
                        protected void terminated() {
                            try {
                                store.close();
                            } catch (RuntimeException e) {
                                closeFailure = e;
                            }
                        }
                    };
        } catch (RuntimeException | Error failure) {
            try {
                store.close();
            } catch (RuntimeException close) {
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
                        new IllegalStateException("Redis replay guard is closed"));
            }
            try {
                executor.execute(
                        () -> {
                            worker.set(true);
                            try {
                                long ttl = retainUntil - clock.millis();
                                if (ttl <= 0) {
                                    throw new IllegalArgumentException(
                                            "Replay registration is already expired");
                                }
                                store.register(issuerNodeId, jti, ttl);
                                if (retainUntil <= clock.millis()) {
                                    throw new IllegalStateException(
                                            "Replay registration expired during storage I/O");
                                }
                                result.complete(null);
                            } catch (Throwable failure) {
                                result.completeExceptionally(failure);
                            } finally {
                                worker.remove();
                            }
                        });
            } catch (RejectedExecutionException rejected) {
                result.completeExceptionally(
                        new IllegalStateException("Redis replay queue is full", rejected));
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
                            worker.set(true);
                            try {
                                if (retainUntil <= clock.millis()) {
                                    result.complete(ReplayDecision.UNAVAILABLE);
                                    return;
                                }
                                var decision = store.consume(issuerNodeId, jti);
                                result.complete(
                                        retainUntil <= clock.millis()
                                                ? ReplayDecision.UNAVAILABLE
                                                : decision);
                            } catch (RuntimeException failure) {
                                result.completeExceptionally(failure);
                            } finally {
                                worker.remove();
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
        if (Boolean.TRUE.equals(worker.get())) {
            return;
        }
        try {
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                // Accepted work is bounded and every command has a configured timeout.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted closing Redis replay state", e);
        }
        if (closeFailure != null) {
            throw new IllegalStateException("Failed to close Redis replay state", closeFailure);
        }
    }

    private static Store openStore(
            ReplayGuardConfiguration.Redis settings, String targetNodeId, Clock clock)
            throws IOException {
        requireTarget(targetNodeId);
        Objects.requireNonNull(clock, "clock");
        return new JedisReplayStore(settings);
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
