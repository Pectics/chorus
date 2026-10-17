package me.pectics.chorus.core.api;

import java.util.Map;
import java.util.Objects;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;

/** Caller must supply an authenticated session; Phase 1 requires empty extensions. */
public record IssueTicketRequest(
        String targetNodeId, AuthenticatedIdentity identity, Map<String, String> extensions) {
    public IssueTicketRequest {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(identity, "identity");
        extensions = Map.copyOf(extensions);
    }
}
