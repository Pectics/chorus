package me.pectics.chorus.core.api;

import java.util.Objects;

/**
 * Expected rejection. Messages contain only the error code, never ticket contents or key material.
 */
public final class TicketRejectedException extends RuntimeException {
    private final TicketError code;

    public TicketRejectedException(TicketError code) {
        super(Objects.requireNonNull(code, "code").name());
        this.code = code;
    }

    public TicketError code() {
        return code;
    }
}
