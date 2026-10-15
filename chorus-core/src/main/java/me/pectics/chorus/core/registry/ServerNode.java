package me.pectics.chorus.core.registry;

import java.util.Map;
import java.util.Set;

/** Administrator-approved routing data. A directory entry does not grant issuer trust. */
public record ServerNode(
        String nodeId,
        String displayName,
        String host,
        int port,
        Set<String> tags,
        Map<String, String> metadata) {
    public ServerNode {
        if (nodeId == null
                || !nodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || displayName == null
                || displayName.isBlank()
                || host == null
                || host.isBlank()
                || host.chars().anyMatch(Character::isWhitespace)
                || port < 1
                || port > 65535) {
            throw new IllegalArgumentException("Invalid server node");
        }
        tags = Set.copyOf(tags);
        metadata = Map.copyOf(metadata);
    }
}
