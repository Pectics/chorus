/*
 * @context
 * refs: ARCHITECTURE.md, section 3.2
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/servers.schema.json
 * sync: Keep routing fields aligned with the server node registry schema and bootstrap assembly.
 * @endcontext
 */
package me.pectics.chorus.core.node;

/** Administrator-approved routing data. A server entry does not grant issuer trust. */
public record ServerNode(String nodeId, String displayName, String host, int port) {
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
    }
}
