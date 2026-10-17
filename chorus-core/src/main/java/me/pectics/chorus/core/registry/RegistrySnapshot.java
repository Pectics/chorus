package me.pectics.chorus.core.registry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable approved server snapshot. Stale is informational; the provider enforces usability
 * limits.
 */
public record RegistrySnapshot(
        Map<String, ServerNode> servers, Instant refreshedAt, boolean stale) {
    public RegistrySnapshot {
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
