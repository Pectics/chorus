package me.pectics.chorus.core.api;

import java.util.Objects;
import me.pectics.chorus.core.identity.ReceiverLoginContext;

/** Null payload represents a missing Cookie. All non-null payloads are defensively copied. */
public record VerifyTicketRequest(byte[] cookiePayload, ReceiverLoginContext loginContext) {
    public VerifyTicketRequest {
        Objects.requireNonNull(loginContext, "loginContext");
        cookiePayload = cookiePayload == null ? null : cookiePayload.clone();
    }

    @Override
    public byte[] cookiePayload() {
        return cookiePayload == null ? null : cookiePayload.clone();
    }

    @Override
    public String toString() {
        return "VerifyTicketRequest[payloadPresent=" + (cookiePayload != null) + "]";
    }
}
