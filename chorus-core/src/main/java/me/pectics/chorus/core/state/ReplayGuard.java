/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 3.8
 * sync: Keep expiration tolerance and durable consumption aligned with engine verification.
 * @endcontext
 */
package me.pectics.chorus.core.state;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * Atomic, thread-safe consumption after all other checks. Calling consume must not block on I/O.
 */
public interface ReplayGuard extends AutoCloseable {
    /**
     * Completes ACCEPTED only after durable commit. Retains consumption through expiresAt plus the
     * engine's configured clock skew. Unknown commit outcomes must reject and must never restore a
     * consumed ticket. Implementations must use the same immutable tolerance as the engine.
     */
    CompletionStage<ReplayDecision> consume(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt);

    /** Releases owned resources; callers close off platform I/O threads. */
    @Override
    void close();
}
