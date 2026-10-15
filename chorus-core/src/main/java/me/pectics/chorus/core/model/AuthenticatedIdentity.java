package me.pectics.chorus.core.model;

import java.util.Objects;

/** Only trusted platform authentication or a verified transfer may create this value. */
public record AuthenticatedIdentity(IdentityClaim claim, String validatedByNodeId) {
    public AuthenticatedIdentity {
        Objects.requireNonNull(claim, "claim");
        if (validatedByNodeId == null || validatedByNodeId.isBlank()) {
            throw new IllegalArgumentException("validatedByNodeId is required");
        }
    }
}
