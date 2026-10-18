/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/local/LocalReplayGuard.java
 * sync: Local storage records consumption without sender registration and closes after work drains.
 * @endcontext
 */
package me.pectics.chorus.core.replay.local;

import java.sql.SQLException;
import me.pectics.chorus.core.replay.ReplayGuardStore;

/** Receiver-local storage owned by one guard; consumption and close must be serialized. */
public interface LocalReplayGuardStore extends ReplayGuardStore, AutoCloseable {
    /** Releases storage after all consumption ends; close may block and report storage errors. */
    @Override
    void close() throws SQLException;
}
