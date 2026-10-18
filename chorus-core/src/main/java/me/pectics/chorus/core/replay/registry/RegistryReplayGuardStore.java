/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/ReplayGuardRegistry.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/replay/registry/RegistryReplayGuard.java
 * sync: Confirm registration before issuance and close storage only after accepted work drains.
 * @endcontext
 */
package me.pectics.chorus.core.replay.registry;

import java.sql.SQLException;
import me.pectics.chorus.core.registry.ReplayGuardRegistry;
import me.pectics.chorus.core.replay.ReplayGuardStore;

/** Synchronous registry storage owned by one guard; operations and close must be serialized. */
public interface RegistryReplayGuardStore
        extends ReplayGuardStore, ReplayGuardRegistry, AutoCloseable {
    /** Releases storage after all operations end; close may block and report storage errors. */
    @Override
    void close() throws SQLException;
}
