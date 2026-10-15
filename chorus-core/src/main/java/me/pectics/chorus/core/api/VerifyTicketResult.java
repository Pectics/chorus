package me.pectics.chorus.core.api;

import java.util.Objects;
import me.pectics.chorus.core.model.AuthenticatedIdentity;
import me.pectics.chorus.core.strategy.ResolvedPlayerProfile;

/** Returned only after durable consumption; later platform failures do not restore the ticket. */
public record VerifyTicketResult(
        ResolvedPlayerProfile resolvedProfile, AuthenticatedIdentity retainedIdentity, String jti) {
    public VerifyTicketResult {
        Objects.requireNonNull(resolvedProfile, "resolvedProfile");
        Objects.requireNonNull(retainedIdentity, "retainedIdentity");
        Objects.requireNonNull(jti, "jti");
    }
}
