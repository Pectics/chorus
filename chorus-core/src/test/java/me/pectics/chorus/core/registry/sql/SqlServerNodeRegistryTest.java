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
import me.pectics.chorus.core.config.model.RegistryConfiguration.Cache;
import me.pectics.chorus.core.registry.ServerNode;
import org.junit.jupiter.api.Test;

class SqlServerNodeRegistryTest {
    private final AtomicLong ticker = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final Cache cache = new Cache(Duration.ofMinutes(1), Duration.ofMinutes(2));

    @Test
    void lookupsUseImmutableMemoryAndExpireByMonotonicAgeEvenWithAFixedWallClock()
            throws Exception {
        var store = new FakeStore();
        try (var registry = registry(store)) {
            var original = registry.snapshot();
            for (int i = 0; i < 20; i++) assertThat(registry.find("backend")).isPresent();
            assertThat(store.loads.get()).isEqualTo(1);
            assertThatThrownBy(() -> original.servers().clear())
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
        var store = new FakeStore();
        try (var registry = registry(store)) {
            var original = registry.snapshot();
            ticker.set(Duration.ofSeconds(30).toNanos());
            store.fail = true;
            assertThatThrownBy(
                            () -> registry.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .hasRootCauseMessage("SQL server directory refresh failed")
                    .satisfies(
                            failure -> {
                                var trace = new java.io.StringWriter();
                                failure.printStackTrace(new java.io.PrintWriter(trace));
                                assertThat(trace.toString()).doesNotContain("secret-password");
                            });
            assertThat(registry.snapshot().servers()).isEqualTo(original.servers());
            assertThat(registry.snapshot().refreshedAt()).isEqualTo(original.refreshedAt());
            assertThat(registry.snapshot().stale()).isTrue();
            store.fail = false;
            store.nodes = Map.of();
            assertThatThrownBy(
                            () -> registry.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS))
                    .hasRootCauseMessage("SQL server directory refresh failed");
            ticker.set(Duration.ofMinutes(2).toNanos());
            assertThatThrownBy(registry::snapshot).isInstanceOf(IllegalStateException.class);
            store.nodes =
                    Map.of(
                            "backend",
                            new ServerNode("backend", "Updated", "other.example.net", 25566));
            registry.refresh().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertThat(registry.snapshot().stale()).isFalse();
            assertThat(registry.find("backend").orElseThrow().port()).isEqualTo(25566);
            assertThat(original.servers().get("backend").port()).isEqualTo(25565);
        }
    }

    @Test
    void concurrentRefreshesShareOneAttemptAndCloseDrainsItWithoutBlockingItsCallback()
            throws Exception {
        var store = new FakeStore();
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
                    .hasRootCauseMessage("SQL server registry is closed");
        } finally {
            store.release.countDown();
            registry.close();
        }
    }

    @Test
    void periodicRefreshUsesTheWorkerAndStopsAfterClose() throws Exception {
        var store = new FakeStore();
        var refreshed = new CompletableFuture<String>();
        store.onRefresh = refreshed;
        var settings = new Cache(Duration.ofMillis(20), Duration.ofSeconds(1));
        try (var registry = new SqlServerNodeRegistry(store, settings, Set.of("backend"), clock)) {
            assertThat(refreshed.get(2, TimeUnit.SECONDS)).isEqualTo("chorus-sql-registry");
            assertThat(registry.find("backend")).isPresent();
        }
        assertThat(store.closed.get()).isEqualTo(1);
    }

    @Test
    void initializationFailureClosesTheStoreAndNeverPublishesAnEmptyFallback() {
        var store = new FakeStore();
        store.fail = true;
        assertThatThrownBy(() -> registry(store)).isInstanceOf(SQLException.class);
        assertThat(store.closed.get()).isEqualTo(1);
        var missing = new FakeStore();
        missing.nodes = Map.of();
        assertThatThrownBy(() -> registry(missing)).isInstanceOf(IllegalArgumentException.class);
        assertThat(missing.closed.get()).isEqualTo(1);
    }

    private SqlServerNodeRegistry registry(FakeStore store) throws SQLException {
        return new SqlServerNodeRegistry(store, cache, Set.of("backend"), clock, ticker::get);
    }

    private static final class FakeStore implements SqlServerNodeRegistry.Store {
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
                onRefresh.complete(Thread.currentThread().getName());
            return nodes;
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
