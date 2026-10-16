package me.pectics.chorus.core.registry;

import java.util.Optional;

/** Thread-safe administrator approvals; lookups never perform I/O or fetch token-supplied URLs. */
public interface TrustedIssuerRegistry {
    /** Returns the local issuer approval, including disabled issuers; unknown issuers are empty. */
    Optional<TrustedIssuer> findByIssuer(String issuerNodeId);
}
