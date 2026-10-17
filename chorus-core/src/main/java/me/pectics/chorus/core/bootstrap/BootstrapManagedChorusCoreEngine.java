/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3 and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/bootstrap/ChorusCoreBootstrap.java
 * sync: Bootstrap owns the SQL registry; the engine retains ownership of its replay guard.
 * @endcontext
 */
package me.pectics.chorus.core.bootstrap;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.IssueTicketResult;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.api.VerifyTicketResult;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.sql.SqlServerNodeRegistry;

/**
 * Owns the bootstrap-created engine and SQL server node registry; closes both even if one fails.
 */
final class BootstrapManagedChorusCoreEngine implements ChorusCoreEngine {
    private final ChorusCoreEngine delegate;
    private final SqlServerNodeRegistry registry;
    private final AtomicBoolean closed = new AtomicBoolean();

    BootstrapManagedChorusCoreEngine(ChorusCoreEngine delegate, SqlServerNodeRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public ServerNodeRegistry getServerNodeRegistry() {
        return delegate.getServerNodeRegistry();
    }

    @Override
    public String getCookieKey() {
        return delegate.getCookieKey();
    }

    @Override
    public CompletionStage<IssueTicketResult> issueTicket(IssueTicketRequest request) {
        return delegate.issueTicket(request);
    }

    @Override
    public CompletionStage<VerifyTicketResult> verifyAndResolve(VerifyTicketRequest request) {
        return delegate.verifyAndResolve(request);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        RuntimeException failure = null;
        try {
            delegate.close();
        } catch (RuntimeException close) {
            failure = close;
        }
        try {
            registry.close();
        } catch (RuntimeException close) {
            if (failure == null) failure = close;
            else failure.addSuppressed(close);
        }
        if (failure != null) throw failure;
    }
}
