/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.5 and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/policy/IssuerTrustPolicy.java
 * sync: Sender target permissions do not replace the receiver's local issuer approval.
 * @endcontext
 */
package me.pectics.chorus.core.policy;

import java.util.Objects;
import java.util.Set;
import me.pectics.chorus.core.model.IdentityClaim;

/** Sender-controlled identity permissions and permitted destinations. */
public record IssuancePolicy(
        IssuerTrustPolicy identityAssertionPolicy, Set<String> allowedTargets) {
    public IssuancePolicy {
        Objects.requireNonNull(identityAssertionPolicy, "identityAssertionPolicy");
        allowedTargets = Set.copyOf(allowedTargets);
        if (allowedTargets.isEmpty()
                || allowedTargets.stream()
                        .anyMatch(t -> !t.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"))) {
            throw new IllegalArgumentException("Explicit target permissions are required");
        }
    }

    public boolean permits(IdentityClaim identity, String targetNodeId) {
        return identityAssertionPolicy.permits(identity) && allowedTargets.contains(targetNodeId);
    }
}
