/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.2
 * sync: Publish complete routing snapshots without reading or modifying issuer approvals.
 * @endcontext
 */
package me.pectics.chorus.core.registry.file;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;

/**
 * Thread-safe server node registry populated from administrator-provided files; reads perform no
 * I/O.
 */
public final class FileServerNodeRegistry implements ServerNodeRegistry {
    private final AtomicReference<ServerNodeRegistrySnapshot> state = new AtomicReference<>();

    public FileServerNodeRegistry(ServerNodeRegistrySnapshot snapshot) {
        replace(snapshot);
    }

    /** Atomically publishes the complete registry snapshot; null leaves the old snapshot intact. */
    public void replace(ServerNodeRegistrySnapshot snapshot) {
        state.set(Objects.requireNonNull(snapshot, "snapshot"));
    }

    @Override
    public ServerNodeRegistrySnapshot snapshot() {
        return state.get();
    }
}
