/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.2
 * sync: Publish complete routing snapshots without reading or modifying issuer approvals.
 * @endcontext
 */
package me.pectics.chorus.core.registry.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.registry.RegistrySnapshot;
import me.pectics.chorus.core.registry.ServerNodeRegistry;

/** Thread-safe in-memory directory loaded by the administrator; reads never perform file I/O. */
public final class ConfigServerNodeRegistry implements ServerNodeRegistry {
    private final AtomicReference<RegistrySnapshot> state = new AtomicReference<>();

    public ConfigServerNodeRegistry(RegistrySnapshot snapshot) {
        replace(snapshot);
    }

    /**
     * Atomically publishes the complete immutable directory; null leaves the old snapshot intact.
     */
    public void replace(RegistrySnapshot snapshot) {
        state.set(Objects.requireNonNull(snapshot, "snapshot"));
    }

    @Override
    public RegistrySnapshot snapshot() {
        return state.get();
    }
}
