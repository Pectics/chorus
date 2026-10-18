/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/registry/RegistryReplayGuard.java
 * sync: Confirm registration before issuance and close storage only after accepted work drains.
 * @endcontext
 */
package me.pectics.chorus.core.replay.registry;

import java.sql.SQLException;
import me.pectics.chorus.core.replay.ReplayGuardStore;

/** Synchronous registry storage owned by one guard; operations and close must be serialized. */
public interface RegistryReplayGuardStore extends ReplayGuardStore, AutoCloseable {
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

    /** Releases storage after all operations end; close may block and report storage errors. */
    @Override
    void close() throws SQLException;
}
