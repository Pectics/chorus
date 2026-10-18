/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 5.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/local/LocalReplayGuard.java;
 *          chorus-core/src/integrationTest/java/me/pectics/chorus/core/LocalReplayLifecycleTest.java
 * sync: Verify guard scheduling and deadlines independently of SQLite persistence tests.
 * @endcontext
 */
package me.pectics.chorus.core.replay.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.pectics.chorus.core.replay.ReplayDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LocalReplayGuardTest {
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    private final MutableClock clock = new MutableClock();
    private final FakeStore store = new FakeStore();

    @Test
    void consumptionRunsOnTheWorkerAndCompletesAfterStorageReturns() throws Exception {
        store.block = true;
        try (var guard = new LocalReplayGuard(store, "backend", clock, 4)) {
            try {
                var pending =
                        guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(pending).isNotDone();
                assertThat(store.worker).isNotSameAs(Thread.currentThread());
                assertThat(store.consumption)
                        .isEqualTo(
                                new Consumption(
                                        "gateway",
                                        "ticket",
                                        NOW.plusSeconds(45).toEpochMilli(),
                                        NOW.toEpochMilli()));
                store.release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
            } finally {
                store.release.countDown();
            }
        }
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ReplayDecision.class)
    void storageOutcomesPassThroughTheGuard(ReplayDecision decision) throws Exception {
        store.decision = decision;
        try (var guard = new LocalReplayGuard(store, "backend", clock, 4)) {
            assertThat(
                            guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(decision);
        }
    }

    @Test
    void queuedConsumptionUsesExecutionTimeAndSkipsExpiredTickets() throws Exception {
        store.block = true;
        try (var guard = new LocalReplayGuard(store, "backend", clock, 4)) {
            try {
                var running =
                        guard.consume("gateway", "running", "backend", NOW.plusSeconds(120))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                var expired =
                        guard.consume("gateway", "expired", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                var valid =
                        guard.consume("gateway", "valid", "backend", NOW.plusSeconds(120))
                                .toCompletableFuture();
                clock.now = NOW.plusSeconds(45);
                store.release.countDown();
                assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(expired.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThat(valid.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(store.calls.get()).isEqualTo(2);
                assertThat(store.consumption)
                        .isEqualTo(
                                new Consumption(
                                        "gateway",
                                        "valid",
                                        NOW.plusSeconds(150).toEpochMilli(),
                                        NOW.plusSeconds(45).toEpochMilli()));
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void storageConfirmationAtTheRetentionBoundaryCannotAcceptTheTicket() throws Exception {
        store.block = true;
        try (var guard = new LocalReplayGuard(store, "backend", clock, 4)) {
            try {
                var pending =
                        guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                .toCompletableFuture();
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = NOW.plusSeconds(45);
                store.release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThat(store.calls.get()).isEqualTo(1);
            } finally {
                store.release.countDown();
            }
        }
    }

    @Test
    void completionCallbackCanCloseTheGuardWithoutWaitingForItsOwnWorker() throws Exception {
        store.block = true;
        try (var guard = new LocalReplayGuard(store, "backend", clock, 4)) {
            try {
                var pending = guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15));
                assertThat(store.entered.await(5, TimeUnit.SECONDS)).isTrue();
                var callback = pending.thenRun(guard::close).toCompletableFuture();
                store.release.countDown();
                callback.get(5, TimeUnit.SECONDS);
            } finally {
                store.release.countDown();
            }
        }
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void unexpectedStorageFailureCompletesTheOperationExceptionally() {
        store.consumeFailure = new IllegalStateException("storage failure");
        try (var guard = new LocalReplayGuard(store, "backend", clock, 4)) {
            assertThatThrownBy(
                            () ->
                                    guard.consume(
                                                    "gateway",
                                                    "ticket",
                                                    "backend",
                                                    NOW.plusSeconds(15))
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasCause(store.consumeFailure);
        }
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void externalCloseReportsTheStorageCloseFailure() {
        store.closeFailure = new SQLException("close failure");
        assertThatThrownBy(
                        () -> {
                            try (var guard = new LocalReplayGuard(store, "backend", clock, 4)) {
                                guard.consume("gateway", "ticket", "backend", NOW.plusSeconds(15))
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS);
                            }
                        })
                .isInstanceOf(IllegalStateException.class)
                .hasCause(store.closeFailure);
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void invalidArgumentsDoNotTransferStoreOwnership() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalReplayGuard(store, null, clock, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalReplayGuard(store, "bad id", clock, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalReplayGuard(store, "backend", clock, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocalReplayGuard(store, "backend", clock, -1));
        assertThatNullPointerException()
                .isThrownBy(() -> new LocalReplayGuard(null, "backend", clock, 1));
        assertThatNullPointerException()
                .isThrownBy(() -> new LocalReplayGuard(store, "backend", null, 1));
        assertThat(store.closes.get()).isZero();
    }

    private record Consumption(String issuer, String jti, long retainUntilMillis, long nowMillis) {}

    private static final class FakeStore implements LocalReplayGuardStore {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private volatile boolean block;
        private volatile Thread worker;
        private volatile Consumption consumption;
        private volatile ReplayDecision decision = ReplayDecision.ACCEPTED;
        private volatile RuntimeException consumeFailure;
        private volatile SQLException closeFailure;

        @Override
        public ReplayDecision consume(
                String issuer, String jti, long retainUntilMillis, long nowMillis) {
            calls.incrementAndGet();
            worker = Thread.currentThread();
            consumption = new Consumption(issuer, jti, retainUntilMillis, nowMillis);
            entered.countDown();
            if (block) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release storage");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (consumeFailure != null) {
                throw consumeFailure;
            }
            return decision;
        }

        @Override
        public void close() throws SQLException {
            closes.incrementAndGet();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = NOW;

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
