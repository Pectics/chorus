/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.2
 * sync: Replace complete approved snapshots only after routing and trust validation succeeds.
 * @endcontext
 */
package me.pectics.chorus.core.registry.staticconfig;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.registry.RegistrySnapshot;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.TrustedIssuerRegistry;

/**
 * In-memory reads of administrator-loaded static configuration, with atomic snapshot replacement.
 */
public final class StaticRegistries implements ServerNodeRegistry, TrustedIssuerRegistry {
    private record IssuerKey(String issuer, String kid) {}

    private record State(RegistrySnapshot directory, Map<IssuerKey, TrustedIssuer> issuers) {}

    private final AtomicReference<State> state = new AtomicReference<>();

    public StaticRegistries(RegistrySnapshot snapshot, Collection<TrustedIssuer> issuers) {
        replace(snapshot, issuers);
    }

    /** Validates before replacement; invalid changes leave the previous approved state intact. */
    public void replace(RegistrySnapshot snapshot, Collection<TrustedIssuer> issuers) {
        var approved = new HashMap<IssuerKey, TrustedIssuer>();
        for (var issuer : issuers) {
            if (!snapshot.servers().keySet().containsAll(issuer.policy().allowedTargets())) {
                throw new IllegalArgumentException("Trust policy references an unknown target");
            }
            if (approved.put(new IssuerKey(issuer.issuerNodeId(), issuer.key().keyId()), issuer)
                    != null) {
                throw new IllegalArgumentException("Duplicate issuer/key binding");
            }
        }
        state.set(new State(snapshot, Map.copyOf(approved)));
    }

    @Override
    public RegistrySnapshot snapshot() {
        return state.get().directory();
    }

    @Override
    public Optional<TrustedIssuer> find(String issuerNodeId, String keyId) {
        return Optional.ofNullable(state.get().issuers().get(new IssuerKey(issuerNodeId, keyId)));
    }

    @Override
    public boolean containsIssuer(String issuerNodeId) {
        return state.get().issuers().keySet().stream()
                .anyMatch(k -> k.issuer().equals(issuerNodeId));
    }
}
