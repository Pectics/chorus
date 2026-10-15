/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.8, 3.9, and 7.2
 * sync: Require explicit operational limits; examples must not become unverified product defaults.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.time.Duration;
import java.util.Objects;
import me.pectics.chorus.core.strategy.IdentityStrategy;
import me.pectics.chorus.core.strategy.IssuerTrustPolicy;

/**
 * Validated immutable settings. Operational parameters are explicit until architecture 7.2 is
 * settled.
 */
public record CoreConfiguration(
        String nodeId,
        boolean sender,
        boolean receiver,
        String cookieKey,
        Duration ticketTtl,
        Duration maxTicketLifetime,
        Duration clockSkew,
        int maxTicketBytes,
        int maxPropertyBytes,
        boolean requireTransferIntent,
        IdentityStrategy identityStrategy,
        IssuerTrustPolicy issuancePolicy) {
    public CoreConfiguration {
        Objects.requireNonNull(ticketTtl, "ticketTtl");
        Objects.requireNonNull(maxTicketLifetime, "maxTicketLifetime");
        Objects.requireNonNull(clockSkew, "clockSkew");
        Objects.requireNonNull(identityStrategy, "identityStrategy");
        if (nodeId == null
                || !nodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || cookieKey == null
                || !cookieKey.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid node ID or namespaced Cookie key");
        }
        if (ticketTtl.isZero()
                || ticketTtl.isNegative()
                || ticketTtl.getNano() != 0
                || maxTicketLifetime.compareTo(ticketTtl) < 0
                || maxTicketLifetime.getNano() != 0
                || clockSkew.isNegative()
                || clockSkew.getNano() != 0
                || maxTicketBytes < 1
                || maxTicketBytes > 5120
                || maxPropertyBytes < 1
                || maxPropertyBytes > maxTicketBytes) {
            throw new IllegalArgumentException("Invalid ticket lifetime or size limits");
        }
        maxTicketLifetime.plus(clockSkew).toMillis();
        if (sender && issuancePolicy == null) {
            throw new IllegalArgumentException("Sender requires explicit issuance permissions");
        }
    }
}
