package me.pectics.chorus.core.identity;

/** Pure, nonblocking profile mapping after issuer authorization and external identity checks. */
@FunctionalInterface
public interface IdentityResolutionPolicy {
    ResolvedPlayerProfile resolve(IdentityClaim identity);
}
