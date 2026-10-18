/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.3
 * sync: Keep cache expiry, failure recovery, and resource ownership independent of JDBC services.
 * @endcontext
 */
package me.pectics.chorus.core.registry.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.registry.RegistryConfiguration.Cache;
import org.junit.jupiter.api.Test;

class SqlServerNodeRegistryTest {
    private final AtomicLong ticker = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final Cache cache = new Cache(Duration.ofMinutes(1), Duration.ofMinutes(2));

    @Test
    void lookupsUseImmutableMemoryAndExpireByMonotonicAgeEvenWithAFixedWallClock()
            throws Exception {
        var store = new FakeServerNodeStore();
        try (var registry = registry(store)) {
            var original = registry.snapshot();
            for (int i = 0; i < 20; i++) assertThat(registry.find("backend")).isPresent();
            assertThat(store.loads.get()).isEqualTo(1);
            assertThatThrownBy(() -> original.serverNodes().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            ticker.set(Duration.ofMinutes(1).toNanos());
            assertThat(registry.snapshot().stale()).isTrue();
            ticker.set(Duration.ofMinutes(2).toNanos() - 1);
            assertThat(registry.find("backend")).isPresent();
            ticker.incrementAndGet();
            assertThatThrownBy(registry::snapshot).isInstanceOf(IllegalStateException.class);
            assertThat(store.loads.get()).isEqualTo(1);
        }
        assertThat(store.closed.get()).isEqualTo(1);
    }

    @Test
    void failedAndInvalidRefreshesNeverExtendTheOldSnapshotAndSuccessRecoversIt() throws Exception {
        var store = new FakeServerNodeStore();
        try (var registry = registry(store)) {
            var original = registry.snapshot();
            ticker.set(Duration.ofSeconds(30).toNanos());
            store.fail = true;
            assertThatThrownBy(
                            () -> registry.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .hasRootCauseMessage("SQL server node registry refresh failed")
                    .satisfies(
                            failure -> {
                                var trace = new java.io.StringWriter();
                                failure.printStackTrace(new java.io.PrintWriter(trace));
                                assertThat(trace.toString()).doesNotContain("secret-password");
                            });
            assertThat(registry.snapshot().serverNodes()).isEqualTo(original.serverNodes());
            assertThat(registry.snapshot().refreshedAt()).isEqualTo(original.refreshedAt());
            assertThat(registry.snapshot().stale()).isTrue();
            store.fail = false;
            store.nodes = Map.of();
            assertThatThrownBy(
                            () -> registry.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .hasRootCauseMessage("SQL server node registry refresh failed");
            ticker.set(Duration.ofMinutes(2).toNanos());
            assertThatThrownBy(registry::snapshot).isInstanceOf(IllegalStateException.class);
            store.nodes =
                    Map.of(
                            "backend",
                            new ServerNode("backend", "Updated", "other.example.net", 25566));
            registry.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(registry.snapshot().stale()).isFalse();
            assertThat(registry.find("backend").orElseThrow().port()).isEqualTo(25566);
            assertThat(original.serverNodes().get("backend").port()).isEqualTo(25565);
        }
    }

    @Test
    void concurrentRefreshesShareOneAttemptAndCloseDrainsItWithoutBlockingItsCallback()
            throws Exception {
        var store = new FakeServerNodeStore();
        var registry = registry(store);
        try {
            store.entered = new CountDownLatch(1);
            store.release = new CountDownLatch(1);
            var first = registry.refresh();
            assertThat(store.entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = registry.refresh();
            var done = second.thenRun(registry::close).toCompletableFuture();
            store.release.countDown();
            first.toCompletableFuture().get(2, TimeUnit.SECONDS);
            done.get(2, TimeUnit.SECONDS);
            registry.close();
            assertThat(store.loads.get()).isEqualTo(2);
            assertThat(store.closed.get()).isEqualTo(1);
            assertThatThrownBy(registry::snapshot).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                            () -> registry.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .hasRootCauseMessage("SQL server node registry is closed");
        } finally {
            store.release.countDown();
            registry.close();
        }
    }

    @Test
    void periodicRefreshUsesTheWorkerAndStopsAfterClose() throws Exception {
        var store = new FakeServerNodeStore();
        var refreshed = new CompletableFuture<String>();
        store.onRefresh = refreshed;
        var settings = new Cache(Duration.ofMillis(20), Duration.ofSeconds(1));
        try (var registry = new SqlServerNodeRegistry(store, settings, Set.of("backend"), clock)) {
            assertThat(refreshed.get(2, TimeUnit.SECONDS)).isEqualTo("chorus-sql-registry|true");
            assertThat(registry.find("backend")).isPresent();
        }
        assertThat(store.closed.get()).isEqualTo(1);
    }

    @Test
    void refreshAndPeriodicWorkersAreDaemonThreads() throws Exception {
        var store = new FakeServerNodeStore();
        var refreshed = new CompletableFuture<String>();
        store.onRefresh = refreshed;
        var settings = new Cache(Duration.ofMillis(20), Duration.ofSeconds(1));
        try (var registry = new SqlServerNodeRegistry(store, settings, Set.of("backend"), clock)) {
            assertThat(refreshed.get(2, TimeUnit.SECONDS)).endsWith("|true");
            var workers =
                    Thread.getAllStackTraces().keySet().stream()
                            .filter(thread -> thread.getName().startsWith("chorus-sql-"))
                            .toList();
            assertThat(workers).isNotEmpty();
            assertThat(workers).allMatch(Thread::isDaemon);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void externalCloseWaitsAcrossTerminationPeriodsUntilRefreshFinishes() throws Exception {
        var store = new FakeServerNodeStore();
        var registry = registry(store);
        try {
            store.entered = new CountDownLatch(1);
            store.release = new CountDownLatch(1);
            var refreshing = registry.refresh();
            assertThat(store.entered.await(2, TimeUnit.SECONDS)).isTrue();

            var closingStarted = new CountDownLatch(1);
            var closing =
                    CompletableFuture.runAsync(
                            () -> {
                                closingStarted.countDown();
                                registry.close();
                            });
            assertThat(closingStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> closing.get(1_300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(store.closed.get()).isZero();

            store.release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            refreshing.toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(store.closed.get()).isEqualTo(1);
        } finally {
            store.release.countDown();
            registry.close();
        }
    }

    @Test
    void closeReportsStoreFailuresAfterDrainingAcceptedWork() throws Exception {
        var store = new FakeServerNodeStore();
        store.closeFailure = new IllegalStateException("store close failed");
        var registry = registry(store);
        assertThatThrownBy(registry::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("store close failed");
        assertThat(store.closed.get()).isEqualTo(1);
    }

    @Test
    void interruptedCloseRestoresTheInterruptFlagAndReleasesStorage() throws Exception {
        var store = new FakeServerNodeStore();
        var registry = registry(store);
        try {
            store.entered = new CountDownLatch(1);
            store.release = new CountDownLatch(1);
            var refreshing = registry.refresh();
            assertThat(store.entered.await(2, TimeUnit.SECONDS)).isTrue();

            var closing = new CountDownLatch(1);
            var caught = new java.util.concurrent.atomic.AtomicBoolean();
            var interruptedFlag = new java.util.concurrent.atomic.AtomicBoolean();
            var closer =
                    new Thread(
                            () -> {
                                closing.countDown();
                                try {
                                    registry.close();
                                } catch (IllegalStateException failure) {
                                    caught.set(true);
                                    interruptedFlag.set(Thread.currentThread().isInterrupted());
                                }
                            },
                            "registry-closer");
            closer.start();
            assertThat(closing.await(2, TimeUnit.SECONDS)).isTrue();
            closer.interrupt();
            closer.join(2_000);
            assertThat(closer.isAlive()).isFalse();
            assertThat(caught).isTrue();
            assertThat(interruptedFlag).isTrue();

            store.release.countDown();
            refreshing.toCompletableFuture().get(2, TimeUnit.SECONDS);
        } finally {
            store.release.countDown();
            registry.close();
        }
        assertThat(store.closed.get()).isEqualTo(1);
    }

    @Test
    void initializationFailureClosesTheStoreAndNeverPublishesAnEmptyFallback() {
        var store = new FakeServerNodeStore();
        store.fail = true;
        assertThatThrownBy(() -> registry(store)).isInstanceOf(SQLException.class);
        assertThat(store.closed.get()).isEqualTo(1);
        var missing = new FakeServerNodeStore();
        missing.nodes = Map.of();
        assertThatThrownBy(() -> registry(missing)).isInstanceOf(IllegalArgumentException.class);
        assertThat(missing.closed.get()).isEqualTo(1);
    }

    private SqlServerNodeRegistry registry(FakeServerNodeStore store) throws SQLException {
        return new SqlServerNodeRegistry(store, cache, Set.of("backend"), clock, ticker::get);
    }

    private static final class FakeServerNodeStore
            implements SqlServerNodeRegistry.ServerNodeStore {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        volatile Map<String, ServerNode> nodes =
                Map.of(
                        "backend",
                        new ServerNode("backend", "Backend", "backend.example.net", 25565));
        volatile boolean fail;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        volatile CompletableFuture<String> onRefresh;
        volatile RuntimeException closeFailure;

        @Override
        public Map<String, ServerNode> load() throws SQLException {
            int count = loads.incrementAndGet();
            if (fail) throw new SQLException("secret-password");
            if (count > 1 && entered != null) {
                entered.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS))
                        throw new SQLException("Test worker timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted test worker");
                }
            }
            if (count > 1 && onRefresh != null)
                onRefresh.complete(
                        Thread.currentThread().getName() + "|" + Thread.currentThread().isDaemon());
            return nodes;
        }

        @Override
        public void close() {
            closed.incrementAndGet();
            if (closeFailure != null) throw closeFailure;
        }
    }
}
