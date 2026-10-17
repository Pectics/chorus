/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.5 and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/trust/IssuerTrustPolicy.java
 * sync: Sender target permissions do not replace the receiver's local issuer approval.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import java.util.Objects;
import java.util.Set;
import me.pectics.chorus.core.identity.IdentityClaim;
import me.pectics.chorus.core.trust.IssuerTrustPolicy;

/** Sender-controlled identity permissions and permitted destinations. */
public record TicketIssuancePolicy(
        IssuerTrustPolicy identityAssertionPolicy, Set<String> allowedTargets) {
    public TicketIssuancePolicy {
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
