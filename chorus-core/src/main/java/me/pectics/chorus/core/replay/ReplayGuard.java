/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 3.8
 * sync: Keep expiration tolerance and durable consumption aligned with engine verification.
 * @endcontext
 */
package me.pectics.chorus.core.replay;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Atomic, thread-safe replay protection. Register and consume must not block on I/O. */
public interface ReplayGuard extends AutoCloseable {
    /**
     * Completes only after durable registration. Retains registration through expiresAt plus the
     * shared clock tolerance. Unknown commit outcomes must fail and must never hand out the ticket.
     * LOCAL does not pre-register and completes immediately.
     */
    default CompletionStage<Void> register(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Completes ACCEPTED only after durable commit. Retains consumption through expiresAt plus the
     * shared clock tolerance. Unknown commit outcomes must reject and must never restore a consumed
     * ticket.
     */
    CompletionStage<ReplayDecision> consume(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt);

    /** Releases owned resources; callers close off platform I/O threads. */
    @Override
    void close();
}
