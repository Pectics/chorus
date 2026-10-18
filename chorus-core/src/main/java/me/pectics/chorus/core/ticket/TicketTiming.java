/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.8, and 3.9
 * sync: Use one immutable tolerance for ticket verification and replay retention on every backend.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import java.time.Duration;

/** Fixed clock tolerance shared by verification and every replay backend. */
public final class TicketTiming {
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private TicketTiming() {}
}
