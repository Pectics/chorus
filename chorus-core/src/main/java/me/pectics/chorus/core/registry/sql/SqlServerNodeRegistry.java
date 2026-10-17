/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.3, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/sql/JdbcServerNodeStore.java
 * sync: Publish complete routing snapshots; SQL refresh must never modify local issuer approvals.
 * @endcontext
 */
package me.pectics.chorus.core.registry.sql;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.registry.RegistryConfiguration.Cache;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;

/**
 * SQL server node registry with bounded, serial background I/O and a hard monotonic cache age.
 * Construction loads the first snapshot and may block. Lookup never performs I/O. Ownership
 * transfers after argument validation; a failed initial load also closes the store. Close outside
 * event loops. Local issuer trust is a separate resource.
 */
public final class SqlServerNodeRegistry implements ServerNodeRegistry, AutoCloseable {
    /** Blocking database boundary, called only during startup or on the registry worker. */
    public interface ServerNodeStore extends AutoCloseable {
        Map<String, ServerNode> load() throws SQLException;

        @Override
        void close();
    }

    private record ServerNodeRegistryCacheState(
            ServerNodeRegistrySnapshot snapshot, long loadedAt, boolean failed) {}

    private final Object lifecycle = new Object();
    private final ServerNodeStore store;
    private final Cache cache;
    private final Set<String> requiredNodes;
    private final Clock clock;
    private final LongSupplier ticker;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> periodic;
    private volatile ServerNodeRegistryCacheState state;
    private volatile boolean closed;
    private volatile Thread worker;
    private volatile RuntimeException closeFailure;
    private CompletableFuture<Void> pending;

    public SqlServerNodeRegistry(
            ServerNodeStore store, Cache cache, Set<String> requiredNodes, Clock clock)
            throws SQLException {
        this(store, cache, requiredNodes, clock, System::nanoTime);
    }

    SqlServerNodeRegistry(
            ServerNodeStore store,
            Cache cache,
            Set<String> requiredNodes,
            Clock clock,
            LongSupplier ticker)
            throws SQLException {
        this.store = Objects.requireNonNull(store, "store");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.requiredNodes = Set.copyOf(requiredNodes);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        ThreadPoolExecutor io = null;
        ScheduledExecutorService timer = null;
        try {
            state = load();
            io =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(1),
                            task -> {
                                var thread = new Thread(task, "chorus-sql-registry");
                                thread.setDaemon(true);
                                worker = thread;
                                return thread;
                            }) {
                        @Override
                        protected void terminated() {
                            try {
                                store.close();
                            } catch (RuntimeException failure) {
                                closeFailure = failure;
                            }
                        }
                    };
            executor = io;
            timer =
                    Executors.newSingleThreadScheduledExecutor(
                            task -> {
                                var thread = new Thread(task, "chorus-sql-refresh");
                                thread.setDaemon(true);
                                return thread;
                            });
            scheduler = timer;
            periodic =
                    scheduler.scheduleWithFixedDelay(
                            this::refresh,
                            cache.refreshInterval().toMillis(),
                            cache.refreshInterval().toMillis(),
                            TimeUnit.MILLISECONDS);
        } catch (SQLException | RuntimeException | Error failure) {
            if (timer != null) timer.shutdownNow();
            if (io != null) io.shutdown();
            else {
                try {
                    store.close();
                } catch (RuntimeException close) {
                    failure.addSuppressed(close);
                }
            }
            throw failure;
        }
    }

    @Override
    public ServerNodeRegistrySnapshot snapshot() {
        var current = state;
        long age = ticker.getAsLong() - current.loadedAt();
        if (closed || age >= cache.maxStale().toNanos()) {
            throw new IllegalStateException("SQL server node registry is unavailable");
        }
        var snapshot = current.snapshot();
        if (current.failed() || age >= cache.refreshInterval().toNanos()) {
            return new ServerNodeRegistrySnapshot(
                    snapshot.serverNodes(), snapshot.refreshedAt(), true);
        }
        return snapshot;
    }

    /**
     * Requests one refresh without caller I/O. Concurrent requests share an in-flight attempt;
     * callbacks may run on the worker and must not block it. Failures preserve the old cache age.
     * Database diagnostics are sanitized because driver exceptions can include credentials.
     */
    public CompletionStage<Void> refresh() {
        synchronized (lifecycle) {
            if (closed)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("SQL server node registry is closed"));
            if (pending != null) return pending.minimalCompletionStage();
            var result = new CompletableFuture<Void>();
            pending = result;
            executor.execute(
                    () -> {
                        RuntimeException failure = null;
                        try {
                            var next = load();
                            synchronized (lifecycle) {
                                if (!closed) state = next;
                            }
                        } catch (SQLException | RuntimeException invalid) {
                            var previous = state;
                            state =
                                    new ServerNodeRegistryCacheState(
                                            previous.snapshot(), previous.loadedAt(), true);
                            failure =
                                    new IllegalStateException(
                                            "SQL server node registry refresh failed");
                        }
                        synchronized (lifecycle) {
                            pending = null;
                        }
                        if (failure == null) result.complete(null);
                        else result.completeExceptionally(failure);
                    });
            return result.minimalCompletionStage();
        }
    }

    private ServerNodeRegistryCacheState load() throws SQLException {
        var serverNodes = store.load();
        var snapshot = new ServerNodeRegistrySnapshot(serverNodes, clock.instant(), false);
        if (!snapshot.serverNodes().keySet().containsAll(requiredNodes)) {
            throw new IllegalArgumentException(
                    "Node and issuance targets must exist in the SQL server node registry");
        }
        return new ServerNodeRegistryCacheState(snapshot, ticker.getAsLong(), false);
    }

    /**
     * Stops refreshes, drains accepted work, then releases storage; safe from a worker callback.
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            periodic.cancel(false);
            scheduler.shutdownNow();
            executor.shutdown();
        }
        if (Thread.currentThread() == worker) return;
        try {
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                // JDBC acquire, connect, query, and socket waits have explicit bounds.
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted closing SQL server node registry");
        }
        if (closeFailure != null) throw closeFailure;
    }
}
