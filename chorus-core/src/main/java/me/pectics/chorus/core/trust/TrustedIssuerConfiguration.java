/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2, 3.7, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/trust/config/ConfigTrustedIssuerLoader.java
 * sync: Keep approved key paths separate from loaded verification material and runtime publication.
 * @endcontext
 */
package me.pectics.chorus.core.trust;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;

/**
 * Immutable local approval settings before key loading. Invalid issuer IDs or empty key paths throw
 * IllegalArgumentException; null required values throw NullPointerException. Copies the path list,
 * performs no I/O, and contains no loaded key material.
 */
public record TrustedIssuerConfiguration(
        String issuerNodeId,
        SignatureAlgorithm algorithm,
        List<Path> keyPaths,
        boolean enabled,
        IssuerTrustPolicy policy) {
    public TrustedIssuerConfiguration {
        if (issuerNodeId == null || !issuerNodeId.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid issuer node ID");
        }
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(policy, "policy");
        keyPaths = List.copyOf(keyPaths);
        if (keyPaths.isEmpty()) {
            throw new IllegalArgumentException("Issuer requires approved key paths");
        }
    }
}
