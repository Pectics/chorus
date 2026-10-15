package me.pectics.chorus.core.crypto;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/** Uses Java 21 Ed25519; each operation owns its mutable JCA Signature instance. */
public final class Ed25519SignatureService implements SignatureService {
    @Override
    public byte[] sign(byte[] data, SigningKey key) {
        return sign(data, key.privateKey());
    }

    byte[] sign(byte[] data, PrivateKey key) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    @Override
    public boolean verify(byte[] data, byte[] signature, VerificationKey key) {
        return verify(data, signature, key.publicKey());
    }

    boolean verify(byte[] data, byte[] signature, PublicKey key) {
        if (signature.length != 64) {
            return false;
        }
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (java.security.SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 verification failed", e);
        }
    }
}
