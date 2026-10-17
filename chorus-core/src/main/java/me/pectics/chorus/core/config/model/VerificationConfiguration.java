/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.8, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/state/sqlite/SqliteReplayGuard.java
 * sync: Use this clock skew for both ticket verification and durable replay retention.
 * @endcontext
 */
package me.pectics.chorus.core.config.model;

import java.time.Duration;
import java.util.Objects;
import me.pectics.chorus.core.policy.IdentityPolicy;

/**
 * Receiver time and identity rules. Maximum lifetime is positive and skew is nonnegative, both in
 * whole seconds. Invalid values throw IllegalArgumentException, nulls throw NullPointerException,
 * and a lifetime plus skew that cannot be represented in milliseconds throws ArithmeticException.
 */
public record VerificationConfiguration(
        Duration maxLifetime,
        Duration clockSkew,
        boolean enforceTransferIntent,
        IdentityPolicy identityPolicy) {
    public VerificationConfiguration {
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        Objects.requireNonNull(clockSkew, "clockSkew");
        Objects.requireNonNull(identityPolicy, "identityPolicy");
        if (maxLifetime.isZero()
                || maxLifetime.isNegative()
                || maxLifetime.getNano() != 0
                || clockSkew.isNegative()
                || clockSkew.getNano() != 0) {
            throw new IllegalArgumentException("Invalid verification time limits");
        }
        maxLifetime.plus(clockSkew).toMillis();
    }
}
