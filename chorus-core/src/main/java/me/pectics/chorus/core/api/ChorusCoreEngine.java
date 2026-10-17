/*
 * @context
 * refs: ARCHITECTURE.md, section 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuard.java
 * sync: Keep asynchronous failure semantics and consumption order aligned.
 * @endcontext
 */
package me.pectics.chorus.core.api;

import java.util.concurrent.CompletionStage;
import me.pectics.chorus.core.registry.ServerNodeRegistry;

/**
 * Thread-safe platform-neutral entry point. Operations never wait for database I/O on the caller.
 * Expected refusals complete exceptionally with TicketRejectedException. Programming errors in
 * request construction throw immediately; initialization and unexpected infrastructure errors are
 * separate exceptions. Never join returned stages on platform I/O threads.
 */
public interface ChorusCoreEngine extends AutoCloseable {
    ServerNodeRegistry getServerRegistry();

    String getCookieKey();

    CompletionStage<IssueTicketResult> issueTicket(IssueTicketRequest request);

    CompletionStage<VerifyTicketResult> verifyAndResolve(VerifyTicketRequest request);

    /** Close off I/O threads; rejects new operations and releases owned resources. */
    @Override
    void close();
}
