/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuard.java
 * sync: Keep storage synchronous; guards own scheduling and deadline checks around storage I/O.
 * @endcontext
 */
package me.pectics.chorus.core.replay;

/**
 * Synchronous, atomic replay consumption. Calls may block and must run outside platform I/O
 * threads. Implementations define their concurrency and resource ownership requirements; the owning
 * guard supplies scheduling and closes storage after accepted work drains.
 */
public interface ReplayGuardStore {
    /**
     * Consumes one issuer/ticket pair, returning ACCEPTED only after storage confirms the
     * operation. REPLAYED means already consumed or, for shared storage, not registered;
     * UNAVAILABLE means storage failed, the outcome is unknown, or retention has expired. Unknown
     * outcomes must never restore a consumed ticket or retry through another backend.
     *
     * @param issuerNodeId nonblank issuer ID, validated by the guard
     * @param jti nonblank ticket ID, validated by the guard
     * @param retainUntilMillis exclusive retention deadline in epoch milliseconds, including the
     *     shared clock tolerance
     * @param nowMillis execution time in epoch milliseconds, sampled by the guard on its worker
     */
    ReplayDecision consume(String issuerNodeId, String jti, long retainUntilMillis, long nowMillis);
}
