package me.pectics.chorus.core.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

/** Local signing material bound to exactly one algorithm. String representations redact secrets. */
public sealed interface SigningKey permits SigningKey.Ed25519, HmacSha256Key {
    SignatureAlgorithm algorithm();

    /** Rejects non-Ed25519 and mismatched key pairs. */
    record Ed25519(PrivateKey privateKey, PublicKey publicKey) implements SigningKey {
        public Ed25519 {
            Ed25519Keys.requireEd25519(publicKey);
            Ed25519Keys.requireEd25519(privateKey);
            var service = new Ed25519SignatureService();
            byte[] probe =
                    "Chorus key pair validation"
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (!service.verify(probe, service.sign(probe, privateKey), publicKey)) {
                throw new IllegalArgumentException("Signing key pair does not match");
            }
        }

        @Override
        public SignatureAlgorithm algorithm() {
            return SignatureAlgorithm.ED25519;
        }

        @Override
        public String toString() {
            return "SigningKey[Ed25519]";
        }
    }
}
