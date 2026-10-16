package me.pectics.chorus.core.crypto;

import java.util.List;
import java.util.Objects;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.TrustedIssuerRegistry;

/** Verification lookup delegates to the same approvals used for receiver policy checks. */
public final class StaticKeyRing implements KeyRing {
    private final SigningKey signingKey;
    private final TrustedIssuerRegistry issuers;

    public StaticKeyRing(SigningKey signingKey, TrustedIssuerRegistry issuers) {
        this.signingKey = signingKey;
        this.issuers = Objects.requireNonNull(issuers, "issuers");
    }

    @Override
    public SigningKey activeSigningKey() {
        if (signingKey == null) {
            throw new IllegalStateException("No active signing key");
        }
        return signingKey;
    }

    @Override
    public List<VerificationKey> findTrustedKeys(String issuerNodeId) {
        return issuers.findByIssuer(issuerNodeId)
                .filter(TrustedIssuer::enabled)
                .map(TrustedIssuer::keys)
                .orElse(List.of());
    }
}
