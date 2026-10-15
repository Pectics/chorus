package me.pectics.chorus.core.registry;

import java.util.Objects;
import me.pectics.chorus.core.crypto.VerificationKey;
import me.pectics.chorus.core.strategy.IssuerTrustPolicy;

/** Approval of one issuer/key binding and its receiver-controlled scope. */
public record TrustedIssuer(
        String issuerNodeId, VerificationKey key, boolean enabled, IssuerTrustPolicy policy) {
    public TrustedIssuer {
        if (issuerNodeId == null || !issuerNodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid issuer node ID");
        }
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(policy, "policy");
    }
}
