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
     * Completes only after storage confirms registration, before expiresAt plus the shared clock
     * tolerance. SQL waits for commit; Redis durability depends on server persistence settings for
     * both SET and DEL. Unknown outcomes must fail and must never hand out the ticket. LOCAL does
     * not pre-register and completes immediately.
     */
    default CompletionStage<Void> register(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Completes ACCEPTED only after storage confirms consumption within expiresAt plus the shared
     * clock tolerance. SQLite and SQL wait for commit; Redis requires persistence of acknowledged
     * deletes to guarantee consumption across crashes. Unknown or expired outcomes must reject and
     * must never restore a consumed ticket.
     */
    CompletionStage<ReplayDecision> consume(
            String issuerNodeId, String jti, String targetNodeId, Instant expiresAt);

    /** Releases owned resources; callers close off platform I/O threads. */
    @Override
    void close();
}
