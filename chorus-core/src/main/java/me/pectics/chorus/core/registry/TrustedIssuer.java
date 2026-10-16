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
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.crypto.TicketVerifier;
import me.pectics.chorus.core.policy.IssuerTrustPolicy;

/**
 * Local receiver approval of an issuer under one policy. Copies the verifier list and rejects
 * invalid issuer IDs, empty lists, duplicate verification material, and mixed algorithms. Signing
 * objects are reduced to their verification views so Ed25519 approval never retains a private key.
 */
public record TrustedIssuer(
        String issuerNodeId,
        List<TicketVerifier> verifiers,
        boolean enabled,
        IssuerTrustPolicy policy) {
    public TrustedIssuer {
        if (issuerNodeId == null || !issuerNodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid issuer node ID");
        }
        verifiers =
                List.copyOf(verifiers).stream()
                        .map(
                                verifier ->
                                        verifier instanceof TicketSigner signer
                                                ? signer.verifier()
                                                : verifier)
                        .toList();
        if (verifiers.isEmpty() || new HashSet<>(verifiers).size() != verifiers.size()) {
            throw new IllegalArgumentException("Issuer requires distinct approved keys");
        }
        var algorithm = verifiers.getFirst().algorithm();
        if (verifiers.stream().anyMatch(verifier -> verifier.algorithm() != algorithm)) {
            throw new IllegalArgumentException("Issuer keys must use one algorithm");
        }
        Objects.requireNonNull(policy, "policy");
    }

    public SignatureAlgorithm algorithm() {
        return verifiers.getFirst().algorithm();
    }
}
