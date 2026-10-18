/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 5.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/redis/RedisReplayGuard.java
 * sync: Cover TTL computation, target and expiry rejection, queue overflow, and close.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisReplayGuardTest {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    @TempDir Path directory;
    private final MutableClock clock = new MutableClock(NOW);
    private final FakeStore store = new FakeStore();

    @Test
    void registerComputesTheRetentionTtlAndConsumeUsesTheStoreDecision() throws Exception {
        try (var guard = new RedisReplayGuard(store, "backend", clock, 2, 4)) {
            guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(store.registered).isEqualTo("gateway/ticket");
            assertThat(store.ttlMillis).isEqualTo(45_000);
            store.consumeDecision = ReplayDecision.REPLAYED;
            assertThat(
                            guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.REPLAYED);
        }
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void targetMismatchAndExpiredRetentionNeverTouchTheStore() throws Exception {
        try (var guard = new RedisReplayGuard(store, "backend", clock, 2, 4)) {
            assertThat(
                            guard.consume("gateway", "ticket", "other", NOW.plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.UNAVAILABLE);
            assertThat(
                            guard.consume("gateway", "ticket", "backend", NOW.minusSeconds(30))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.UNAVAILABLE);
            assertThatThrownBy(
                            () ->
                                    guard.register(
                                                    "gateway",
                                                    "ticket",
                                                    "backend",
                                                    NOW.minusSeconds(30))
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThat(store.consumeCalls.get()).isZero();
        assertThat(store.registerCalls.get()).isZero();
    }

    @Test
    void queuedRegistrationSubtractsElapsedTimeFromItsRedisTtl() throws Exception {
        store.block = true;
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 4)) {
            try {
                var running =
                        guard.register("gateway", "running", "backend", NOW.plusSeconds(120))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                var queued =
                        guard.register("gateway", "queued", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                clock.now = NOW.plusSeconds(10);
                store.release.countDown();
                running.get(5, TimeUnit.SECONDS);
                queued.get(5, TimeUnit.SECONDS);
                assertThat(store.registered).isEqualTo("gateway/queued");
                assertThat(store.ttlMillis).isEqualTo(35_000);
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void queuedOperationsAtTheRetentionBoundaryNeverTouchStorage() throws Exception {
        store.block = true;
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 4)) {
            try {
                var running =
                        guard.register("gateway", "running", "backend", NOW.plusSeconds(120))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                var registration =
                        guard.register("gateway", "expired", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                var consumption =
                        guard.consume("gateway", "expired", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                clock.now = NOW.plusSeconds(45);
                store.release.countDown();
                running.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> registration.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalArgumentException.class);
                assertThat(consumption.get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThat(store.registerCalls.get()).isEqualTo(1);
                assertThat(store.consumeCalls.get()).isZero();
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void registrationCannotSucceedAfterStorageCrossesTheRetentionBoundary() throws Exception {
        store.block = true;
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 4)) {
            try {
                var pending =
                        guard.register("gateway", "slow", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = NOW.plusSeconds(45);
                store.release.countDown();
                assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(store.registered).isEqualTo("gateway/slow");
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void consumedTicketIsNotAcceptedWhenStorageCrossesTheRetentionBoundary() throws Exception {
        store.block = true;
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 4)) {
            try {
                var pending =
                        guard.consume("gateway", "slow", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = NOW.plusSeconds(45);
                store.release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThat(store.consumeCalls.get()).isEqualTo(1);
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void registrationFailureIsReportedAndConsumptionStaysUnavailable() throws Exception {
        store.failRegister = true;
        try (var guard = new RedisReplayGuard(store, "backend", clock, 2, 4)) {
            assertThatThrownBy(
                            () ->
                                    guard.register(
                                                    "gateway",
                                                    "ticket",
                                                    "backend",
                                                    NOW.plusSeconds(15))
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            store.consumeDecision = ReplayDecision.UNAVAILABLE;
            assertThat(
                            guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.UNAVAILABLE);
        }
    }

    @Test
    void closeDrainsAcceptedWorkAndRejectsNewOperations() throws Exception {
        store.block = true;
        var guard = new RedisReplayGuard(store, "backend", clock, 1, 4);
        var pending =
                guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                        .toCompletableFuture();
        assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
        var failure = new AtomicReference<Throwable>();
        var closer =
                new Thread(
                        () -> {
                            try {
                                guard.close();
                            } catch (Throwable e) {
                                failure.set(e);
                            }
                        });
        closer.start();
        assertThat(closer.isAlive()).isTrue();
        store.release.countDown();
        pending.get(5, TimeUnit.SECONDS);
        closer.join(5_000);
        assertThat(closer.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(store.closes.get()).isEqualTo(1);
        assertThat(
                        guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS))
                .isEqualTo(ReplayDecision.UNAVAILABLE);
        assertThatThrownBy(
                        () ->
                                guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeFromACompletionCallbackDoesNotDeadlock() throws Exception {
        var guard = new RedisReplayGuard(store, "backend", clock, 1, 4);
        guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                .thenRun(guard::close)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(store.closed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void queueOverflowRejectsWithoutTouchingTheStore() throws Exception {
        store.block = true;
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 1)) {
            var running =
                    guard.register("gateway", "running", "backend", NOW.plusSeconds(15))
                            .toCompletableFuture();
            assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var queued =
                    guard.register("gateway", "queued", "backend", NOW.plusSeconds(15))
                            .toCompletableFuture();
            assertThatThrownBy(
                            () ->
                                    guard.register(
                                                    "gateway",
                                                    "overflow",
                                                    "backend",
                                                    NOW.plusSeconds(15))
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(
                            guard.consume("gateway", "overflow", "backend", NOW.plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.UNAVAILABLE);
            store.release.countDown();
            running.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void constructorRejectsInvalidSettings() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedisReplayGuard(store, null, clock, 1, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedisReplayGuard(store, "bad id", clock, 1, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedisReplayGuard(store, "backend", clock, 0, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedisReplayGuard(store, "backend", clock, 1, 0));
        assertThatNullPointerException()
                .isThrownBy(() -> new RedisReplayGuard(store, "backend", null, 1, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new RedisReplayGuard(null, "backend", clock, 1, 1));
    }

    @Test
    void nullInputsAreRejectedBeforeEnqueueing() {
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 1)) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> guard.register(null, "ticket", "backend", NOW.plusSeconds(15)));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> guard.register("gateway", null, "backend", NOW.plusSeconds(15)));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> guard.consume(null, "ticket", "backend", NOW.plusSeconds(15)));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () -> guard.consume("gateway", null, "backend", NOW.plusSeconds(15)));
            assertThatNullPointerException()
                    .isThrownBy(
                            () -> guard.register("gateway", "ticket", null, NOW.plusSeconds(15)));
            assertThatNullPointerException()
                    .isThrownBy(() -> guard.register("gateway", "ticket", "backend", null));
            assertThatNullPointerException()
                    .isThrownBy(() -> guard.consume("gateway", "ticket", "backend", null));
        }
        assertThat(store.registerCalls.get()).isZero();
        assertThat(store.consumeCalls.get()).isZero();
    }

    @Test
    void consumeFailureCompletesExceptionally() {
        store.consumeFailure = new IllegalStateException("consume failed");
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 1)) {
            assertThatThrownBy(
                            () ->
                                    guard.consume(
                                                    "gateway",
                                                    "ticket",
                                                    "backend",
                                                    NOW.plusSeconds(15))
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void closeFailureIsReported() {
        store.closeFailure = new IllegalStateException("close failed");
        var guard = new RedisReplayGuard(store, "backend", clock, 1, 1);
        assertThatThrownBy(guard::close)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void workerThreadIsDaemon() throws Exception {
        store.block = true;
        try (var guard = new RedisReplayGuard(store, "backend", clock, 1, 1)) {
            var pending =
                    guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                            .toCompletableFuture();
            assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var worker =
                    Thread.getAllStackTraces().keySet().stream()
                            .filter(thread -> thread.getName().startsWith("chorus-redis-replay-"))
                            .findFirst()
                            .orElseThrow();
            assertThat(worker.isDaemon()).isTrue();
            store.release.countDown();
            pending.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void publicConstructorValidatesBeforeOpeningStorageAndReportsCredentialFailures() {
        var settings =
                new ReplayGuardConfiguration.Redis(
                        "localhost",
                        6379,
                        0,
                        "default",
                        directory.resolve("missing-password.txt"),
                        new ReplayGuardConfiguration.Tls(
                                ReplayGuardConfiguration.TlsMode.DISABLED, null),
                        new ReplayGuardConfiguration.Timeouts(
                                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(500)),
                        new ReplayGuardConfiguration.Pool(1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedisReplayGuard(settings, null, clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RedisReplayGuard(settings, "bad id", clock));
        assertThatThrownBy(() -> new RedisReplayGuard(settings, "backend", clock))
                .isInstanceOf(IOException.class);
    }

    private static final class FakeStore implements RedisReplayGuard.Store {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean block;
        private volatile boolean failRegister;
        private volatile RuntimeException closeFailure;
        private volatile RuntimeException consumeFailure;
        private volatile ReplayDecision consumeDecision = ReplayDecision.ACCEPTED;
        private volatile String registered;
        private volatile long ttlMillis;
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger consumeCalls = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void register(String issuerNodeId, String jti, long ttlMillis) {
            registerCalls.incrementAndGet();
            entered.countDown();
            await();
            if (failRegister) {
                throw new IllegalStateException("registration failed");
            }
            this.registered = issuerNodeId + "/" + jti;
            this.ttlMillis = ttlMillis;
        }

        @Override
        public ReplayDecision consume(String issuerNodeId, String jti) {
            consumeCalls.incrementAndGet();
            entered.countDown();
            await();
            if (consumeFailure != null) {
                throw consumeFailure;
            }
            return consumeDecision;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private void await() {
            if (!block) {
                return;
            }
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
