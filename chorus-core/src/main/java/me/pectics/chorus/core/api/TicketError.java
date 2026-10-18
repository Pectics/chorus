/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/engine/DefaultChorusCoreEngine.java
 * sync: Report Registry availability at the control-plane boundary, independently of local trust.
 * @endcontext
 */
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
    REPLAY_REGISTRATION_FAILED,
    TICKET_TOO_LARGE,
    CAPABILITY_DISABLED,
    /** The Registry is available, but the requested server node is absent. */
    UNKNOWN_TARGET,
    /**
     * Registry server node data is unavailable; local trust and replay failures are independent.
     */
    REGISTRY_UNAVAILABLE,
    ENGINE_CLOSED
}
