package me.pectics.chorus.core.crypto;

/** Supported key algorithms and their exact JWS identifiers. */
public enum SignatureAlgorithm {
    ED25519("Ed25519"),
    HMAC_SHA256("HS256");

    private final String jwsName;

    SignatureAlgorithm(String jwsName) {
        this.jwsName = jwsName;
    }

    public String jwsName() {
        return jwsName;
    }

    /** Rejects unknown identifiers, including the legacy EdDSA name. */
    public static SignatureAlgorithm fromJwsName(String name) {
        for (var algorithm : values()) {
            if (algorithm.jwsName.equals(name)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unsupported JWS algorithm");
    }
}
