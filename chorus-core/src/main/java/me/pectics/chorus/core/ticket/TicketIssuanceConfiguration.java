/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.5, 3.8, and 3.9
 * sync: Consumers must check sender TTL against the separate verification maximum lifetime.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import java.time.Duration;
import java.util.Objects;

/**
 * Sender lifetime and permissions. TTL must be positive with whole-second precision; violations
 * throw IllegalArgumentException. Null TTL or policy throws NullPointerException. Receiver trust is
 * independently enforced through approved issuers.
 */
public record TicketIssuanceConfiguration(Duration ttl, TicketIssuancePolicy policy) {
    public TicketIssuanceConfiguration {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(policy, "policy");
        if (ttl.isZero() || ttl.isNegative() || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Invalid issuance lifetime");
        }
    }
}
