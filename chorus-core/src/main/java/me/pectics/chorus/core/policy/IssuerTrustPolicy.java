package me.pectics.chorus.core.policy;

import java.util.Set;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.model.IdentityClaim;

/** Explicit authentication types and providers accepted from an issuer by the local receiver. */
public record IssuerTrustPolicy(Set<AuthType> allowedAuthTypes, Set<String> allowedProviders) {
    public IssuerTrustPolicy {
        allowedAuthTypes = Set.copyOf(allowedAuthTypes);
        allowedProviders = Set.copyOf(allowedProviders);
        if (allowedAuthTypes.isEmpty()
                || allowedProviders.isEmpty()
                || allowedProviders.stream()
                        .anyMatch(p -> !p.matches("[a-z0-9][a-z0-9._/-]{0,127}"))) {
            throw new IllegalArgumentException("Explicit authentication permissions are required");
        }
    }

    public boolean permits(IdentityClaim identity) {
        return allowedAuthTypes.contains(identity.authType())
                && allowedProviders.contains(identity.authProvider());
    }
}
