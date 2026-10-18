/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.4 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketCodec.java
 * sync: Apply the same explicit size and lifetime budgets to file and Java callers.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import java.time.Duration;
import java.util.Objects;

/**
 * Cookie transport, encoding, and lifetime limits. Property bytes cannot exceed the payload budget,
 * which is bounded to 5120 bytes. Compression affects outgoing tickets only. Maximum lifetime is
 * positive whole seconds and must cover issuance when sending. Invalid keys or limits throw
 * IllegalArgumentException; null required values throw NullPointerException.
 */
public record TicketConfiguration(
        String cookieKey,
        int maxPayloadBytes,
        int maxPropertyBytes,
        TicketCompression compression,
        Duration maxLifetime) {
    /** Uses ZSTD when no outgoing encoding is explicitly selected. */
    public TicketConfiguration(
            String cookieKey, int maxPayloadBytes, int maxPropertyBytes, Duration maxLifetime) {
        this(cookieKey, maxPayloadBytes, maxPropertyBytes, TicketCompression.ZSTD, maxLifetime);
    }

    public TicketConfiguration {
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        if (cookieKey == null || !cookieKey.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid namespaced Cookie key");
        }
        if (maxPayloadBytes < 1
                || maxPayloadBytes > 5120
                || maxPropertyBytes < 1
                || maxPropertyBytes > maxPayloadBytes) {
            throw new IllegalArgumentException("Invalid ticket size limits");
        }
        if (maxLifetime.isZero() || maxLifetime.isNegative() || maxLifetime.getNano() != 0) {
            throw new IllegalArgumentException("Invalid maximum ticket lifetime");
        }
        if (maxLifetime.compareTo(Duration.ofMillis(Long.MAX_VALUE).minus(TicketTiming.CLOCK_SKEW))
                > 0) {
            throw new IllegalArgumentException("Maximum ticket lifetime is too large");
        }
    }
}
