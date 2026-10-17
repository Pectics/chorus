/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2 and 3.7
 * related: chorus-core/src/main/java/me/pectics/chorus/core/trust/config/ConfigTrustedIssuerLoader.java
 * sync: Replace local approvals only after the complete document and every approved key validate.
 * @endcontext
 */
package me.pectics.chorus.core.trust.file;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.trust.TrustedIssuer;
import me.pectics.chorus.core.trust.TrustedIssuerStore;

/**
 * File-sourced local approvals with atomic replacement, independent of server discovery and SQL
 * availability.
 */
public final class FileTrustedIssuerStore implements TrustedIssuerStore {
    private final AtomicReference<Map<String, TrustedIssuer>> state = new AtomicReference<>();

    public FileTrustedIssuerStore(Collection<TrustedIssuer> issuers) {
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
