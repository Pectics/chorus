package me.pectics.chorus.core.identity;

import java.util.Objects;
import java.util.UUID;

/** Supplied only after independent authentication, never from an unverified login packet UUID. */
public record VerifiedExternalIdentity(
        String authProvider, String providerSubject, UUID verifiedUuid) {
    public VerifiedExternalIdentity {
        Objects.requireNonNull(authProvider, "authProvider");
        Objects.requireNonNull(providerSubject, "providerSubject");
        Objects.requireNonNull(verifiedUuid, "verifiedUuid");
        if (authProvider.isBlank() || providerSubject.isBlank()) {
            throw new IllegalArgumentException("External authentication identifiers are required");
        }
    }
}
