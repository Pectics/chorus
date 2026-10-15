package me.pectics.chorus.core.crypto;

import java.util.Objects;
import java.util.Optional;
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
    public Optional<VerificationKey> findTrustedKey(String issuerNodeId, String kid) {
        return issuers.find(issuerNodeId, kid)
                .filter(TrustedIssuer::enabled)
                .map(TrustedIssuer::key);
    }
}
