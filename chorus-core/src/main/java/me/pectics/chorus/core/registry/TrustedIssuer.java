/*
 * @context
 * refs: ARCHITECTURE.md, section 3.2
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/trusted-issuers.schema.json
 * sync: Keep all issuer keys under one enablement flag and identity policy.
 * @endcontext
 */
package me.pectics.chorus.core.registry;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import me.pectics.chorus.core.crypto.VerificationKey;
import me.pectics.chorus.core.strategy.IssuerTrustPolicy;

/**
 * Local receiver approval of an issuer under one policy. Copies the key list and rejects invalid
 * issuer IDs, empty lists, and duplicate public keys.
 */
public record TrustedIssuer(
        String issuerNodeId,
        List<VerificationKey> keys,
        boolean enabled,
        IssuerTrustPolicy policy) {
    public TrustedIssuer {
        if (issuerNodeId == null || !issuerNodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid issuer node ID");
        }
        keys = List.copyOf(keys);
        if (keys.isEmpty() || new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException("Issuer requires distinct approved public keys");
        }
        Objects.requireNonNull(policy, "policy");
    }
}
