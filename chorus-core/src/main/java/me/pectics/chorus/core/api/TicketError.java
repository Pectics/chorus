package me.pectics.chorus.core.api;

/** Stable rejection categories; infrastructure initialization errors are reported separately. */
public enum TicketError {
    COOKIE_MISSING,
    MALFORMED_TICKET,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_ALGORITHM,
    UNKNOWN_ISSUER,
    UNTRUSTED_KEY,
    INVALID_SIGNATURE,
    EXPIRED,
    NOT_YET_VALID,
    INVALID_LIFETIME,
    TARGET_MISMATCH,
    TRANSFER_REQUIRED,
    USERNAME_MISMATCH,
    ISSUER_POLICY_DENIED,
    EXTERNAL_IDENTITY_MISMATCH,
    PROFILE_RESOLUTION_FAILED,
    REPLAYED,
    REPLAY_UNAVAILABLE,
    REPLAY_CAPACITY_EXCEEDED,
    TICKET_TOO_LARGE,
    CAPABILITY_DISABLED,
    UNKNOWN_TARGET,
    /** The server list cannot supply an approved, usable snapshot. */
    SERVER_LIST_UNAVAILABLE,
    ENGINE_CLOSED
}
