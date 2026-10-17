package me.pectics.chorus.core.registry;

import java.util.Optional;

/** Thread-safe access to the registry's server list. Reads must not block or perform I/O. */
public interface ServerNodeRegistry {
    /** Throws IllegalStateException when no approved, still-usable snapshot exists. */
    RegistrySnapshot snapshot();

    default Optional<ServerNode> find(String nodeId) {
        return Optional.ofNullable(snapshot().servers().get(nodeId));
    }
}
