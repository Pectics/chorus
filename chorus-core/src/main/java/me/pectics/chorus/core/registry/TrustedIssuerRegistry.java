package me.pectics.chorus.core.registry;

import java.util.Optional;

/** Thread-safe administrator approvals; lookups never perform I/O or fetch token-supplied URLs. */
public interface TrustedIssuerRegistry {
    Optional<TrustedIssuer> find(String issuerNodeId, String keyId);

    boolean containsIssuer(String issuerNodeId);
}
