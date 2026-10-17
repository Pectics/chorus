/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.2
 * sync: Replace complete approved snapshots only after routing and trust validation succeeds.
 * @endcontext
 */
package me.pectics.chorus.core.registry.config;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import me.pectics.chorus.core.registry.RegistrySnapshot;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.TrustedIssuerRegistry;

/** In-memory reads of administrator-loaded configuration, with atomic snapshot replacement. */
public final class ConfigRegistries implements ServerNodeRegistry, TrustedIssuerRegistry {
    private record State(RegistrySnapshot directory, Map<String, TrustedIssuer> issuers) {}

    private final AtomicReference<State> state = new AtomicReference<>();

    public ConfigRegistries(RegistrySnapshot snapshot, Collection<TrustedIssuer> issuers) {
        replace(snapshot, issuers);
    }

    /** Validates before replacement; invalid changes leave the previous approved state intact. */
    public void replace(RegistrySnapshot snapshot, Collection<TrustedIssuer> issuers) {
        var approved = new HashMap<String, TrustedIssuer>();
        for (var issuer : issuers) {
            if (approved.putIfAbsent(issuer.issuerNodeId(), issuer) != null) {
                throw new IllegalArgumentException("Duplicate issuer approval");
            }
        }
        state.set(new State(snapshot, Map.copyOf(approved)));
    }

    @Override
    public RegistrySnapshot snapshot() {
        return state.get().directory();
    }

    @Override
    public Optional<TrustedIssuer> findByIssuer(String issuerNodeId) {
        return Optional.ofNullable(state.get().issuers().get(issuerNodeId));
    }
}
