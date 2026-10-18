/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/local/LocalReplayGuard.java
 * sync: Keep queue draining, callback shutdown, and execution-time expiration covered after storage changes.
 * @endcontext
 */
package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.local.LocalReplayGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class LocalReplayLifecycleTest {
    @TempDir Path directory;

    @Test
    void externalCloseDrainsRunningAndQueuedConsumptionBeforeReleasingStorage() throws Exception {
        Path file = directory.resolve("replay.db");
        var clock = new PausingClock();
        try (var guard = open(file, 3, clock)) {
            try {
                var running = consume(guard, "running");
                await(clock.entered);
                var queued = consume(guard, "queued");
                var closingStarted = new CountDownLatch(1);
                var closing =
                        CompletableFuture.runAsync(
                                () -> {
                                    closingStarted.countDown();
                                    guard.close();
                                });
                await(closingStarted);
                assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                clock.resume();
                closing.get(5, TimeUnit.SECONDS);
                assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(queued.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(consume(guard, "after-close").get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.UNAVAILABLE);
            } finally {
                clock.resume();
            }
        }
        try (var reopened = open(file, 3, Clock.fixed(TestSupport.NOW, ZoneOffset.UTC))) {
            assertThat(consume(reopened, "running").get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.REPLAYED);
            assertThat(consume(reopened, "queued").get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.REPLAYED);
        }
    }

    @Test
    void completionCallbackCanCloseWithoutWaitingForItselfOrDiscardingQueuedWork()
            throws Exception {
        var clock = new PausingClock();
        var callbackClosed = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        try (var guard = open(directory.resolve("replay.db"), 3, clock)) {
            try {
                var first = consume(guard, "first");
                await(clock.entered);
                var callback =
                        first.thenRun(
                                () -> {
                                    guard.close();
                                    callbackClosed.countDown();
                                    await(releaseCallback);
                                });
                var queued = consume(guard, "queued");
                clock.resume();
                await(callbackClosed);
                assertThat(queued).isNotDone();
                assertThat(consume(guard, "after-close").get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.UNAVAILABLE);
                var closing = CompletableFuture.runAsync(guard::close);
                assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                releaseCallback.countDown();
                callback.get(5, TimeUnit.SECONDS);
                closing.get(5, TimeUnit.SECONDS);
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(queued.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
            } finally {
                clock.resume();
                releaseCallback.countDown();
            }
        }
    }

    @Test
    void interruptedExternalCloseRestoresTheFlagAndStillDrainsAcceptedWork() throws Exception {
        var clock = new PausingClock();
        var guard = open(directory.resolve("replay.db"), 3, clock);
        try {
            var running = consume(guard, "interrupted-close");
            await(clock.entered);
            var closing = new CountDownLatch(1);
            var caught = new AtomicBoolean();
            var interruptedFlag = new AtomicBoolean();
            var closer =
                    new Thread(
                            () -> {
                                closing.countDown();
                                try {
                                    guard.close();
                                } catch (IllegalStateException failure) {
                                    caught.set(true);
                                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                                }
                            },
                            "replay-closer");
            closer.start();
            await(closing);
            closer.interrupt();
            closer.join(5_000);
            assertThat(closer.isAlive()).isFalse();
            assertThat(caught).isTrue();
            assertThat(interruptedFlag).isTrue();
            clock.resume();
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
        } finally {
            clock.resume();
            guard.close();
        }
    }

    @Test
    void fullQueueRejectsWithoutConsumingTheTicket() throws Exception {
        Path file = directory.resolve("replay.db");
        var clock = new PausingClock();
        try (var guard = open(file, 1, clock)) {
            try {
                var running = consume(guard, "same");
                await(clock.entered);
                var queued = consume(guard, "same");
                assertThat(consume(guard, "overflow").get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.UNAVAILABLE);
                clock.resume();
                assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(queued.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.REPLAYED);
            } finally {
                clock.resume();
            }
        }
        try (var reopened = open(file, 3, Clock.fixed(TestSupport.NOW, ZoneOffset.UTC))) {
            assertThat(consume(reopened, "overflow").get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.ACCEPTED);
        }
    }

    @Test
    void queuedTicketIsCheckedForExpirationWhenItExecutes() throws Exception {
        var clock = new PausingClock();
        try (var guard = open(directory.resolve("replay.db"), 3, clock)) {
            try {
                var running =
                        guard.consume(
                                        "gateway",
                                        "long-lived",
                                        "backend",
                                        TestSupport.NOW.plusSeconds(60))
                                .toCompletableFuture();
                await(clock.entered);
                var queued = consume(guard, "expired");
                clock.now = TestSupport.NOW.plusSeconds(17);
                clock.resume();
                assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.ACCEPTED);
                assertThat(queued.get(5, TimeUnit.SECONDS)).isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThat(
                                guard.consume(
                                                "gateway",
                                                "expired",
                                                "backend",
                                                TestSupport.NOW.plusSeconds(60))
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.ACCEPTED);
            } finally {
                clock.resume();
            }
        }
    }

    private static LocalReplayGuard open(Path path, int capacity, Clock clock) throws Exception {
        return new LocalReplayGuard(
                path, "backend", Duration.ofSeconds(2), capacity, Duration.ofMillis(500), clock);
    }

    private static CompletableFuture<ReplayDecision> consume(LocalReplayGuard guard, String jti) {
        return guard.consume("gateway", jti, "backend", TestSupport.NOW.plusSeconds(15))
                .toCompletableFuture();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Timed out waiting for lifecycle test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted waiting for lifecycle test coordination", e);
        }
    }

    /**
     * Pauses the first storage clock read to keep consumption in flight during lifecycle changes.
     */
    private static final class PausingClock extends Clock {
        private final AtomicBoolean pause = new AtomicBoolean(true);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile Instant now = TestSupport.NOW;

        private void resume() {
            released.countDown();
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
            if (pause.compareAndSet(true, false)) {
                entered.countDown();
                await(released);
            }
            return now;
        }
    }
}
