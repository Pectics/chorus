package me.pectics.chorus.core.trust;

import java.util.Set;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.identity.IdentityClaim;

/** Explicit authentication types and providers accepted from an issuer by the local receiver. */
public record IssuerTrustPolicy(
        Set<AuthenticationSourceType> allowedAuthSchemes, Set<String> allowedProviders) {
    public IssuerTrustPolicy {
        allowedAuthSchemes = Set.copyOf(allowedAuthSchemes);
        allowedProviders = Set.copyOf(allowedProviders);
        if (allowedAuthSchemes.isEmpty()
                || allowedProviders.isEmpty()
                || allowedProviders.stream()
                        .anyMatch(p -> !p.matches("[a-z0-9][a-z0-9._/-]{0,127}"))) {
            throw new IllegalArgumentException("Explicit authentication permissions are required");
        }
    }

    public boolean permits(IdentityClaim identity) {
        return allowedAuthSchemes.contains(identity.authType())
                && allowedProviders.contains(identity.authProvider());
    }
}
