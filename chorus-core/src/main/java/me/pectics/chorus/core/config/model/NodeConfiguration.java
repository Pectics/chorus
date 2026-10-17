/*
 * @context
 * refs: ARCHITECTURE.md, sections 2.4 and 3.9
 * sync: Keep local role requirements separate from server labels and issuer trust.
 * @endcontext
 */
package me.pectics.chorus.core.config.model;

import java.util.Objects;

/**
 * Local node identity and role. The optional display name is descriptive and does not override
 * server entries. Invalid IDs or blank labels throw IllegalArgumentException; a null role throws
 * NullPointerException.
 */
public record NodeConfiguration(String id, String displayName, NodeRole role) {
    public NodeConfiguration(String id, NodeRole role) {
        this(id, null, role);
    }

    public NodeConfiguration {
        Objects.requireNonNull(role, "role");
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid node ID");
        }
        if (displayName != null && displayName.isBlank()) {
            throw new IllegalArgumentException("Blank node display name");
        }
    }
}
