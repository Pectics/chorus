package me.pectics.chorus.core.crypto;

import java.security.PublicKey;

/** Administrator-approved verification material bound to exactly one algorithm. */
public sealed interface VerificationKey permits VerificationKey.Ed25519, HmacSha256Key {
    SignatureAlgorithm algorithm();

    record Ed25519(PublicKey publicKey) implements VerificationKey {
        public Ed25519 {
            Ed25519Keys.requireEd25519(publicKey);
        }

        @Override
        public SignatureAlgorithm algorithm() {
            return SignatureAlgorithm.ED25519;
        }
    }
}
