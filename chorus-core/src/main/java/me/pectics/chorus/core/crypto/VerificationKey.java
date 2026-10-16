package me.pectics.chorus.core.crypto;

import java.security.PublicKey;

/** An administrator-approved Ed25519 public key. */
public record VerificationKey(PublicKey publicKey) {
    public VerificationKey {
        Ed25519Keys.requireEd25519(publicKey);
    }
}
