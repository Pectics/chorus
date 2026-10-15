package me.pectics.chorus.core.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

/** Active local key pair; private key material is never included in string representations. */
public record SigningKey(String keyId, PrivateKey privateKey, PublicKey publicKey) {
    public SigningKey {
        new VerificationKey(keyId, publicKey);
        Ed25519Keys.requireEd25519(privateKey);
        var service = new Ed25519SignatureService();
        byte[] probe =
                "Chorus key pair validation".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (!service.verify(probe, service.sign(probe, privateKey), publicKey)) {
            throw new IllegalArgumentException("Signing key pair does not match");
        }
    }

    @Override
    public String toString() {
        return "SigningKey[keyId=" + keyId + "]";
    }
}
