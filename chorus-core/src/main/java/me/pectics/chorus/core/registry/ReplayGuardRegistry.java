/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2 and 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuardStore.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/replay/registry/RegistryReplayGuardStore.java
 * sync: Keep this registration data contract synchronous; the replay guard owns asynchronous I/O.
 * @endcontext
 */
package me.pectics.chorus.core.registry;

import java.sql.SQLException;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuardStore;

/**
 * Shared ticket registrations in the Registry, identified by issuer/ticket pairs. These operations
 * may block and belong on a replay guard's worker, independently of server node snapshot access.
 * Implementations define their concurrency and resource ownership requirements.
 */
public interface ReplayGuardRegistry {
    /**
     * Registers a nonblank issuer/ticket pair for the nonnull target, confirming the commit before
     * returning. Times are epoch milliseconds; retention includes the shared clock tolerance and
     * must exceed the worker's execution time. Duplicate registrations and unknown outcomes fail.
     *
     * @throws SQLException if registration fails or its outcome is unknown
     */
    void register(
            String issuerNodeId,
            String jti,
            String targetNodeId,
            long retainUntilMillis,
            long nowMillis)
            throws SQLException;

    /**
     * Atomically removes an unexpired registration. Returns ACCEPTED after commit, REPLAYED when
     * absent or already consumed, and UNAVAILABLE on storage failure, unknown outcome, or expired
     * retention. Inputs and execution timestamps follow the common replay storage contract.
     *
     * @see ReplayGuardStore#consume(String, String, long, long)
     */
    ReplayDecision consume(String issuerNodeId, String jti, long retainUntilMillis, long nowMillis);
}
