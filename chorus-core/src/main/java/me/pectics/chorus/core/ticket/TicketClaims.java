package me.pectics.chorus.core.ticket;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import me.pectics.chorus.core.identity.IdentityClaim;

/** Java representation of versioned claims; wire timestamps use integral NumericDate seconds. */
public record TicketClaims(
        int version,
        String jti,
        String issuerNodeId,
        String targetNodeId,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        IdentityClaim identity,
        Map<String, String> extensions) {
    public TicketClaims {
        Objects.requireNonNull(jti, "jti");
        Objects.requireNonNull(issuerNodeId, "issuerNodeId");
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(identity, "identity");
        extensions = Map.copyOf(extensions);
        if (jti.isBlank()
                || jti.length() > 128
                || issuerNodeId.isBlank()
                || issuerNodeId.length() > 128
                || targetNodeId.isBlank()
                || targetNodeId.length() > 128) {
            throw new IllegalArgumentException("Invalid ticket identifiers");
        }
        if (!extensions.isEmpty()) {
            throw new IllegalArgumentException("Ticket extensions are not supported in Phase 1");
        }
    }
}
