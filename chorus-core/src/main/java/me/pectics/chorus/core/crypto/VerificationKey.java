package me.pectics.chorus.core.crypto;

import java.security.PublicKey;

/** An administrator-approved Ed25519 public key; key IDs are scoped to an issuer. */
public record VerificationKey(String keyId, PublicKey publicKey) {
    public VerificationKey {
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid key ID");
        }
        Ed25519Keys.requireEd25519(publicKey);
    }
}
