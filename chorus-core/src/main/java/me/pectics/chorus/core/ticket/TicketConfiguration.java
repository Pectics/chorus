/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.4 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketCodec.java
 * sync: Apply the same explicit size budgets and default outgoing encoding to file and Java callers.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import java.util.Objects;

/**
 * Cookie transport and encoding limits. Property bytes cannot exceed the payload budget, which is
 * bounded to 5120 bytes. Compression affects outgoing tickets only. Invalid keys or limits throw
 * IllegalArgumentException; null compression throws NullPointerException.
 */
public record TicketConfiguration(
        String cookieKey,
        int maxPayloadBytes,
        int maxPropertyBytes,
        TicketCompression compression) {
    /** Uses ZSTD when no outgoing encoding is explicitly selected. */
    public TicketConfiguration(String cookieKey, int maxPayloadBytes, int maxPropertyBytes) {
        this(cookieKey, maxPayloadBytes, maxPropertyBytes, TicketCompression.ZSTD);
    }

    public TicketConfiguration {
        Objects.requireNonNull(compression, "compression");
        if (cookieKey == null || !cookieKey.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid namespaced Cookie key");
        }
        if (maxPayloadBytes < 1
                || maxPayloadBytes > 5120
                || maxPropertyBytes < 1
                || maxPropertyBytes > maxPayloadBytes) {
            throw new IllegalArgumentException("Invalid ticket size limits");
        }
    }
}
