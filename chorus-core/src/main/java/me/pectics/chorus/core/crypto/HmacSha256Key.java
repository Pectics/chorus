package me.pectics.chorus.core.crypto;

import javax.crypto.spec.SecretKeySpec;

/**
 * Shared 256-bit signing and verification key. Copies input bytes and returns copies through JCA's
 * getEncoded(); string representations never contain key material. Invalid lengths are rejected.
 * Every holder can issue tickets, so distribute only within a mutually trusted group.
 */
public record HmacSha256Key(SecretKeySpec secretKey) implements SigningKey, VerificationKey {
    public static final int BYTES = 32;

    public HmacSha256Key {
        if (secretKey == null
                || !"HmacSHA256".equals(secretKey.getAlgorithm())
                || secretKey.getEncoded().length != BYTES) {
            throw new IllegalArgumentException("A 256-bit HmacSHA256 key is required");
        }
        secretKey = new SecretKeySpec(secretKey.getEncoded(), "HmacSHA256");
    }

    public HmacSha256Key(byte[] bytes) {
        this(new SecretKeySpec(bytes, "HmacSHA256"));
    }

    @Override
    public SignatureAlgorithm algorithm() {
        return SignatureAlgorithm.HMAC_SHA256;
    }

    @Override
    public String toString() {
        return "HmacSha256Key[redacted]";
    }
}
