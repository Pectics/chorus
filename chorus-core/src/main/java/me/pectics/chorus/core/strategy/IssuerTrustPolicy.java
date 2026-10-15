package me.pectics.chorus.core.strategy;

import java.util.Set;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.model.IdentityClaim;

/** Explicit intersection of permitted authentication types, providers, and target nodes. */
public record IssuerTrustPolicy(
        Set<AuthType> allowedAuthTypes, Set<String> allowedProviders, Set<String> allowedTargets) {
    public IssuerTrustPolicy {
        allowedAuthTypes = Set.copyOf(allowedAuthTypes);
        allowedProviders = Set.copyOf(allowedProviders);
        allowedTargets = Set.copyOf(allowedTargets);
        if (allowedAuthTypes.isEmpty()
                || allowedProviders.isEmpty()
                || allowedTargets.isEmpty()
                || allowedProviders.stream()
                        .anyMatch(p -> !p.matches("[a-z0-9][a-z0-9._/-]{0,127}"))
                || allowedTargets.stream()
                        .anyMatch(t -> !t.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"))) {
            throw new IllegalArgumentException(
                    "Explicit authentication and target permissions are required");
        }
    }

    public boolean permits(IdentityClaim identity, String targetNodeId) {
        return allowedAuthTypes.contains(identity.authType())
                && allowedProviders.contains(identity.authProvider())
                && allowedTargets.contains(targetNodeId);
    }
}
