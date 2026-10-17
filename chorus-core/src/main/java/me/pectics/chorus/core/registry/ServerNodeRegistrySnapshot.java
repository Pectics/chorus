/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2 and 3.3
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/ServerNodeRegistry.java
 * sync: Keep configuration and SQL providers aligned on immutable server node snapshots.
 * @endcontext
 */
package me.pectics.chorus.core.registry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable approved server node snapshot. Stale is informational; the provider enforces usability
 * limits.
 */
public record ServerNodeRegistrySnapshot(
        Map<String, ServerNode> servers, Instant refreshedAt, boolean stale) {
    public ServerNodeRegistrySnapshot {
        servers = Map.copyOf(servers);
        Objects.requireNonNull(refreshedAt, "refreshedAt");
        servers.forEach(
                (id, node) -> {
                    if (!id.equals(node.nodeId())) {
                        throw new IllegalArgumentException("Server map key differs from node ID");
                    }
                });
    }
}
