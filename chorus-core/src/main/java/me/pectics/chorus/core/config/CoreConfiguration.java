/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.8, 3.9, and 7.2
 * sync: Require explicit operational limits; examples must not become unverified product defaults.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.time.Duration;
import java.util.Objects;
import me.pectics.chorus.core.policy.IdentityPolicy;
import me.pectics.chorus.core.policy.IssuancePolicy;

/**
 * Immutable settings with explicit operational parameters until architecture 7.2 is settled.
 * Lifetimes and skew use whole seconds; sender TTL is positive and no larger than maxLifetime.
 * Ticket bytes are limited to 5120; property bytes cannot exceed that configured ticket limit.
 * GATEWAY and PEER require an issuance TTL and policy; BACKEND permits a null issuance TTL. Invalid
 * settings throw IllegalArgumentException or ArithmeticException for overflowing durations.
 */
public record CoreConfiguration(
        String nodeId,
        NodeRole role,
        String cookieKey,
        Duration issuanceTtl,
        Duration maxLifetime,
        Duration clockSkew,
        int maxPayloadBytes,
        int maxPropertyBytes,
        boolean enforceTransferIntent,
        IdentityPolicy identityPolicy,
        IssuancePolicy issuancePolicy) {
    public CoreConfiguration {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        Objects.requireNonNull(clockSkew, "clockSkew");
        Objects.requireNonNull(identityPolicy, "identityPolicy");
        if (nodeId == null
                || !nodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || cookieKey == null
                || !cookieKey.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid node ID or namespaced Cookie key");
        }
        if (role.sender()
                        && (issuanceTtl == null
                                || issuanceTtl.isZero()
                                || issuanceTtl.isNegative()
                                || issuanceTtl.getNano() != 0
                                || maxLifetime.compareTo(issuanceTtl) < 0)
                || maxLifetime.isZero()
                || maxLifetime.isNegative()
                || maxLifetime.getNano() != 0
                || clockSkew.isNegative()
                || clockSkew.getNano() != 0
                || maxPayloadBytes < 1
                || maxPayloadBytes > 5120
                || maxPropertyBytes < 1
                || maxPropertyBytes > maxPayloadBytes) {
            throw new IllegalArgumentException("Invalid ticket lifetime or size limits");
        }
        maxLifetime.plus(clockSkew).toMillis();
        if (role.sender() && issuancePolicy == null) {
            throw new IllegalArgumentException("Sender requires explicit issuance permissions");
        }
    }
}
