package me.pectics.chorus.core.policy;

import me.pectics.chorus.core.model.IdentityClaim;

/** Pure, nonblocking profile mapping after issuer authorization and external identity checks. */
@FunctionalInterface
public interface IdentityResolutionPolicy {
    ResolvedPlayerProfile resolve(IdentityClaim identity);
}
