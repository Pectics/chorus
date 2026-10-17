/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2 and 3.7
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/TrustLoader.java
 * sync: Replace local approvals only after the complete document and every approved key validate.
 * @endcontext
 */
package me.pectics.chorus.core.trust.config;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.trust.TrustedIssuer;
import me.pectics.chorus.core.trust.TrustedIssuerStore;

/**
 * Local approvals with atomic replacement, independent of server discovery and SQL availability.
 */
public final class ConfigTrustedIssuerStore implements TrustedIssuerStore {
    private final AtomicReference<Map<String, TrustedIssuer>> state = new AtomicReference<>();

    public ConfigTrustedIssuerStore(Collection<TrustedIssuer> issuers) {
        replace(issuers);
    }

    /**
     * Copies approvals before publication; nulls or duplicate issuer IDs leave old approvals
     * intact.
     */
    public void replace(Collection<TrustedIssuer> issuers) {
        var approved = new HashMap<String, TrustedIssuer>();
        for (var issuer : issuers) {
            if (approved.putIfAbsent(issuer.issuerNodeId(), issuer) != null) {
                throw new IllegalArgumentException("Duplicate issuer approval");
            }
        }
        state.set(Map.copyOf(approved));
    }

    @Override
    public Optional<TrustedIssuer> findByIssuer(String issuerNodeId) {
        return Optional.ofNullable(state.get().get(issuerNodeId));
    }
}
