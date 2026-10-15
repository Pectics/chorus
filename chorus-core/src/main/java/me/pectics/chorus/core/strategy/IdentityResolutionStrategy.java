package me.pectics.chorus.core.strategy;

import me.pectics.chorus.core.model.IdentityClaim;

/** Pure, nonblocking profile mapping after issuer authorization and external identity checks. */
@FunctionalInterface
public interface IdentityResolutionStrategy {
    ResolvedPlayerProfile resolve(IdentityClaim identity);
}
