package me.pectics.chorus.core.model;

import java.util.Objects;

/** Trusted platform context; independentlyVerified is null when no independent check was made. */
public record ReceiverLoginContext(
        String loginUsername, boolean transferred, VerifiedExternalIdentity independentlyVerified) {
    public ReceiverLoginContext {
        Objects.requireNonNull(loginUsername, "loginUsername");
    }
}
