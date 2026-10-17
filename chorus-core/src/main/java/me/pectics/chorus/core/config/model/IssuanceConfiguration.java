/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.5, 3.8, and 3.9
 * sync: Consumers must check sender TTL against the separate verification maximum lifetime.
 * @endcontext
 */
package me.pectics.chorus.core.config.model;

import java.time.Duration;
import java.util.Objects;
import me.pectics.chorus.core.policy.IssuancePolicy;

/**
 * Sender lifetime and permissions. TTL must be positive with whole-second precision; violations
 * throw IllegalArgumentException. Null TTL or policy throws NullPointerException. Receiver trust is
 * independently enforced through approved issuers.
 */
public record IssuanceConfiguration(Duration ttl, IssuancePolicy policy) {
    public IssuanceConfiguration {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(policy, "policy");
        if (ttl.isZero() || ttl.isNegative() || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Invalid issuance lifetime");
        }
    }
}
