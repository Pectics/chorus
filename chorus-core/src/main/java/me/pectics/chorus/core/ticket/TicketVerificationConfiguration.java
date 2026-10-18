/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.8, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/local/LocalReplayGuard.java
 * sync: Receiver-only rules; ticket lifetime limits live in TicketConfiguration.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import java.nio.file.Path;
import java.util.Objects;
import me.pectics.chorus.core.identity.BuiltinIdentityResolutionPolicy;

/**
 * Receiver rules and local trust document path, resolved relative to the main configuration. The
 * path may be null when callers inject an existing trust store directly. Null required rules throw
 * NullPointerException.
 */
public record TicketVerificationConfiguration(
        boolean enforceTransferIntent,
        BuiltinIdentityResolutionPolicy identityResolutionPolicy,
        Path trustedIssuersPath) {
    /** Direct Engine assembly injects approvals instead of loading a trust document. */
    public TicketVerificationConfiguration(
            boolean enforceTransferIntent,
            BuiltinIdentityResolutionPolicy identityResolutionPolicy) {
        this(enforceTransferIntent, identityResolutionPolicy, null);
    }

    public TicketVerificationConfiguration {
        Objects.requireNonNull(identityResolutionPolicy, "identityResolutionPolicy");
    }
}
