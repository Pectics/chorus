/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 5.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/registry/RegistryReplayGuard.java
 * sync: Cover register/consume ordering, target and expiry rejection, queue overflow, and close.
 * @endcontext
 */
package me.pectics.chorus.core.replay.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.registry.RegistryConfiguration.Engine;
import me.pectics.chorus.core.registry.RegistryConfiguration.Pool;
import me.pectics.chorus.core.registry.RegistryConfiguration.Sql;
import me.pectics.chorus.core.registry.RegistryConfiguration.Timeouts;
import me.pectics.chorus.core.registry.RegistryConfiguration.Tls;
import me.pectics.chorus.core.registry.RegistryConfiguration.TlsMode;
import me.pectics.chorus.core.replay.ReplayDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryReplayGuardTest {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    @TempDir Path directory;
    private final MutableClock clock = new MutableClock(NOW);
    private final FakeStore store = new FakeStore();

    @Test
    void registerAndConsumeCompleteAfterTheStoreOperation() throws Exception {
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 4)) {
            guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(store.registered).containsExactly("gateway/ticket/backend");
            store.consumeDecision = ReplayDecision.ACCEPTED;
            assertThat(
                            guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.ACCEPTED);
        }
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void targetMismatchAndExpiredRetentionNeverTouchTheStore() throws Exception {
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 4)) {
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
    void queuedOperationsUseExecutionTimeForCleanupAndConsumption() throws Exception {
        store.block = true;
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 4)) {
            try {
                var running =
                        guard.register("gateway", "running", "backend", NOW.plusSeconds(120))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                var registration =
                        guard.register("gateway", "queued", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                var consumption =
                        guard.consume("gateway", "queued", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                clock.now = NOW.plusSeconds(10);
                store.release.countDown();
                running.get(5, TimeUnit.SECONDS);
                registration.get(5, TimeUnit.SECONDS);
                assertThat(consumption.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(store.registrationTime).isEqualTo(clock.millis());
                assertThat(store.consumptionTime).isEqualTo(clock.millis());
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void queuedOperationsAtTheRetentionBoundaryNeverTouchStorage() throws Exception {
        store.block = true;
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 4)) {
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
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 4)) {
            try {
                var pending =
                        guard.register("gateway", "slow", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = NOW.plusSeconds(45);
                store.release.countDown();
                assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(store.registered).containsExactly("gateway/slow/backend");
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void consumedTicketIsNotAcceptedWhenStorageCrossesTheRetentionBoundary() throws Exception {
        store.block = true;
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 4)) {
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
    void storeFailuresBecomeRegistrationFailureAndUnavailableConsumption() throws Exception {
        store.failRegister = true;
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 4)) {
            assertThatThrownBy(
                            () ->
                                    guard.register(
                                                    "gateway",
                                                    "ticket",
                                                    "backend",
                                                    NOW.plusSeconds(15))
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SQLException.class);
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
        var guard = new RegistryReplayGuard(store, "backend", clock, 4);
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
        var guard = new RegistryReplayGuard(store, "backend", clock, 4);
        guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                .thenRun(guard::close)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertThat(store.closed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void queueOverflowRejectsWithoutTouchingTheStore() throws Exception {
        store.block = true;
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 1)) {
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
                .isThrownBy(() -> new RegistryReplayGuard(store, null, clock, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegistryReplayGuard(store, "bad id", clock, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegistryReplayGuard(store, "backend", clock, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegistryReplayGuard(store, "backend", clock, -1));
        assertThatNullPointerException()
                .isThrownBy(() -> new RegistryReplayGuard(store, "backend", null, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new RegistryReplayGuard(null, "backend", clock, 1));
    }

    @Test
    void nullInputsAreRejectedBeforeEnqueueing() {
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 1)) {
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
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 1)) {
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
        store.closeFailure = new SQLException("close failed");
        var guard = new RegistryReplayGuard(store, "backend", clock, 1);
        assertThatThrownBy(guard::close)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void workerThreadIsDaemon() throws Exception {
        store.block = true;
        try (var guard = new RegistryReplayGuard(store, "backend", clock, 1)) {
            var pending =
                    guard.register("gateway", "ticket", "backend", NOW.plusSeconds(15))
                            .toCompletableFuture();
            assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var worker =
                    Thread.getAllStackTraces().keySet().stream()
                            .filter(thread -> thread.getName().equals("chorus-registry-replay"))
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
                new Sql(
                        Engine.POSTGRESQL,
                        "localhost",
                        5432,
                        "chorus",
                        "chorus_user",
                        directory.resolve("missing-password.txt"),
                        new Tls(TlsMode.DISABLED, null),
                        new Pool(1, Duration.ofMillis(500)),
                        new Timeouts(
                                Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegistryReplayGuard(settings, null, clock));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RegistryReplayGuard(settings, "bad id", clock));
        assertThatThrownBy(() -> new RegistryReplayGuard(settings, "backend", clock))
                .isInstanceOf(IOException.class);
    }

    private static final class FakeStore implements RegistryReplayGuard.Store {
        private final Set<String> registered = new HashSet<>();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean block;
        private volatile boolean failRegister;
        private volatile SQLException closeFailure;
        private volatile RuntimeException consumeFailure;
        private volatile ReplayDecision consumeDecision = ReplayDecision.ACCEPTED;
        private volatile long registrationTime;
        private volatile long consumptionTime;
        private final AtomicInteger registerCalls = new AtomicInteger();
        private final AtomicInteger consumeCalls = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void register(
                String issuerNodeId,
                String jti,
                String targetNodeId,
                long retainUntilMillis,
                long nowMillis)
                throws SQLException {
            registerCalls.incrementAndGet();
            registrationTime = nowMillis;
            entered.countDown();
            await(release);
            if (failRegister) {
                throw new SQLException("secret-detail");
            }
            registered.add(issuerNodeId + "/" + jti + "/" + targetNodeId);
        }

        @Override
        public ReplayDecision consume(
                String issuerNodeId, String jti, long retainUntilMillis, long nowMillis) {
            consumeCalls.incrementAndGet();
            consumptionTime = nowMillis;
            entered.countDown();
            await(release);
            if (consumeFailure != null) {
                throw consumeFailure;
            }
            return consumeDecision;
        }

        @Override
        public void close() throws SQLException {
            closes.incrementAndGet();
            closed.countDown();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private void await(CountDownLatch latch) {
            if (!block) {
                return;
            }
            try {
                latch.await(5, TimeUnit.SECONDS);
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
