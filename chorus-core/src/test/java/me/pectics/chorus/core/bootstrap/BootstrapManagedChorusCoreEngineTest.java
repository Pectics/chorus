/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3 and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/bootstrap/BootstrapManagedChorusCoreEngine.java
 * sync: Verify delegation and the joint lifecycle of the Engine and bootstrap-created SQL registry.
 * @endcontext
 */
package me.pectics.chorus.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.IssueTicketResult;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.api.VerifyTicketResult;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;
import me.pectics.chorus.core.identity.ReceiverLoginContext;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.registry.RegistryConfiguration.Cache;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.sql.SqlServerNodeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("resource")
class BootstrapManagedChorusCoreEngineTest {
    private RecordingEngine delegate;
    private RecordingStore store;
    private SqlServerNodeRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        delegate = new RecordingEngine();
        store = new RecordingStore();
        registry =
                new SqlServerNodeRegistry(
                        store,
                        new Cache(Duration.ofMinutes(1), Duration.ofMinutes(2)),
                        Set.of("backend"),
                        Clock.fixed(TestSupport.NOW, ZoneOffset.UTC));
    }

    @Test
    void delegatesEveryEngineOperationAndRegistryView() {
        var managed = new BootstrapManagedChorusCoreEngine(delegate, registry);
        assertThat(managed.getServerNodeRegistry().snapshot().serverNodes()).containsKey("backend");
        assertThat(managed.getCookieKey()).isEqualTo("chorus:ticket");
        var issue = issueRequest();
        assertThat(managed.issueTicket(issue)).isCompleted();
        var verify = new VerifyTicketRequest(null, new ReceiverLoginContext("Notch", true, null));
        assertThat(managed.verifyAndResolve(verify)).isCompleted();
        assertThat(delegate.issued).isSameAs(issue);
        assertThat(delegate.verified).isSameAs(verify);
    }

    @Test
    void closeReleasesTheEngineAndRegistryExactlyOnce() {
        var managed = new BootstrapManagedChorusCoreEngine(delegate, registry);
        managed.close();
        managed.close();
        assertThat(delegate.closes.get()).isEqualTo(1);
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void closeStillReleasesTheRegistryWhenTheEngineFails() {
        delegate.closeFailure = new IllegalStateException("engine close failed");
        var managed = new BootstrapManagedChorusCoreEngine(delegate, registry);
        assertThatThrownBy(managed::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("engine close failed");
        assertThat(delegate.closes.get()).isEqualTo(1);
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void closeReportsARegistryFailureWhenTheEngineClosedCleanly() {
        store.closeFailure = new IllegalStateException("registry close failed");
        var managed = new BootstrapManagedChorusCoreEngine(delegate, registry);
        assertThatThrownBy(managed::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("registry close failed");
        assertThat(delegate.closes.get()).isEqualTo(1);
        assertThat(store.closes.get()).isEqualTo(1);
    }

    @Test
    void closeKeepsBothFailuresWithTheEngineFailurePrimary() {
        delegate.closeFailure = new IllegalStateException("engine close failed");
        store.closeFailure = new IllegalStateException("registry close failed");
        var managed = new BootstrapManagedChorusCoreEngine(delegate, registry);
        assertThatThrownBy(managed::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("engine close failed")
                .satisfies(
                        failure -> {
                            assertThat(failure.getSuppressed()).hasSize(1);
                            assertThat(failure.getSuppressed()[0])
                                    .hasMessage("registry close failed");
                        });
        assertThat(store.closes.get()).isEqualTo(1);
    }

    private static IssueTicketRequest issueRequest() {
        return new IssueTicketRequest(
                "backend", new AuthenticatedIdentity(TestSupport.identity(), "gateway"), Map.of());
    }

    private static final class RecordingEngine implements ChorusCoreEngine {
        private final AtomicInteger closes = new AtomicInteger();
        private IssueTicketRequest issued;
        private VerifyTicketRequest verified;
        private RuntimeException closeFailure;

        @Override
        public ServerNodeRegistry getServerNodeRegistry() {
            return () -> TestSupport.serverNodeRegistry().snapshot();
        }

        @Override
        public String getCookieKey() {
            return "chorus:ticket";
        }

        @Override
        public CompletionStage<IssueTicketResult> issueTicket(IssueTicketRequest request) {
            issued = request;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<VerifyTicketResult> verifyAndResolve(VerifyTicketRequest request) {
            verified = request;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class RecordingStore implements SqlServerNodeRegistry.ServerNodeStore {
        private final AtomicInteger closes = new AtomicInteger();
        private RuntimeException closeFailure;

        @Override
        public Map<String, ServerNode> load() {
            return Map.of("backend", TestSupport.node("backend"));
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (closeFailure != null) throw closeFailure;
        }
    }
}
