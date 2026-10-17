/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2 and 3.7
 * sync: Keep issuer approvals independent of server discovery and server node registry refreshes.
 * @endcontext
 */
package me.pectics.chorus.core.trust;

import java.util.Optional;

/** Thread-safe administrator approvals; lookups never perform I/O or fetch token-supplied URLs. */
public interface TrustedIssuerStore {
    /** Returns the local issuer approval, including disabled issuers; unknown issuers are empty. */
    Optional<TrustedIssuer> findByIssuer(String issuerNodeId);
}
