package me.pectics.chorus.core.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;

/**
 * Thread-safe Ed25519 and HS256 operations selected by key type, never by untrusted ticket data.
 */
public final class JcaSignatureService implements SignatureService {
    private final Ed25519SignatureService ed25519 = new Ed25519SignatureService();

    @Override
    public byte[] sign(byte[] data, SigningKey key) {
        return switch (key) {
            case SigningKey.Ed25519 ed -> ed25519.sign(data, ed);
            case HmacSha256Key hmac -> mac(data, hmac);
        };
    }

    @Override
    public boolean verify(byte[] data, byte[] signature, VerificationKey key) {
        return switch (key) {
            case VerificationKey.Ed25519 ed -> ed25519.verify(data, signature, ed);
            case HmacSha256Key hmac ->
                    signature.length == HmacSha256Key.BYTES
                            && MessageDigest.isEqual(mac(data, hmac), signature);
        };
    }

    private static byte[] mac(byte[] data, HmacSha256Key key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key.secretKey());
            return mac.doFinal(data);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 operation failed", failure);
        }
    }
}
