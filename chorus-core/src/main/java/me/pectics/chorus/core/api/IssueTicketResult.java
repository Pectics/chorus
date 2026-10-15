package me.pectics.chorus.core.api;

import java.time.Instant;
import java.util.Objects;
import me.pectics.chorus.core.registry.ServerNode;

/** Immutable transfer output; payload bytes are copied both on construction and access. */
public record IssueTicketResult(
        ServerNode targetServer,
        String cookieNamespacedKey,
        byte[] cookiePayload,
        String jti,
        Instant expiresAt) {
    public IssueTicketResult {
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(cookieNamespacedKey, "cookieNamespacedKey");
        Objects.requireNonNull(jti, "jti");
        Objects.requireNonNull(expiresAt, "expiresAt");
        cookiePayload = cookiePayload.clone();
    }

    @Override
    public byte[] cookiePayload() {
        return cookiePayload.clone();
    }

    @Override
    public String toString() {
        return "IssueTicketResult[jti=" + jti + ", expiresAt=" + expiresAt + "]";
    }
}
