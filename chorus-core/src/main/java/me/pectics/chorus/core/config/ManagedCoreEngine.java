/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3 and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/CoreBootstrap.java
 * sync: Bootstrap-created SQL resources close with the engine; injected engine collaborators stay caller-owned.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.IssueTicketResult;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.api.VerifyTicketResult;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.sql.SqlServerNodeRegistry;

/** Owns only resources created by SQL bootstrap; closes both even if one close fails. */
final class ManagedCoreEngine implements ChorusCoreEngine {
    private final ChorusCoreEngine delegate;
    private final SqlServerNodeRegistry registry;
    private final AtomicBoolean closed = new AtomicBoolean();

    ManagedCoreEngine(ChorusCoreEngine delegate, SqlServerNodeRegistry registry) {
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
