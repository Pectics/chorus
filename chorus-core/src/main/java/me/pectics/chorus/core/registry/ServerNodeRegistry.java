/*
 * @context
 * refs: ARCHITECTURE.md, section 3.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/ServerNodeRegistrySnapshot.java
 * sync: Both server node providers publish the same immutable snapshot without hot-path I/O.
 * @endcontext
 */
package me.pectics.chorus.core.registry;

import java.util.Optional;

/** Thread-safe server node registry. Reads must not block or perform I/O. */
public interface ServerNodeRegistry {
    /** Throws IllegalStateException when no approved, still-usable snapshot exists. */
    ServerNodeRegistrySnapshot snapshot();

    default Optional<ServerNode> find(String nodeId) {
        return Optional.ofNullable(snapshot().serverNodes().get(nodeId));
    }
}
