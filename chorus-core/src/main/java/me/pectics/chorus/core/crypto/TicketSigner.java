package me.pectics.chorus.core.crypto;

/**
 * Verification plus the ability to create signatures. Implementations own their signing material
 * and return newly allocated signatures. Null data throws NullPointerException; JCA infrastructure
 * failures throw IllegalStateException. Signing never performs file or database I/O.
 */
public sealed interface TicketSigner extends TicketVerifier permits Ed25519.Signer, HmacSha256 {
    byte[] sign(byte[] data);

    /** Returns public-only Ed25519 verification, or this same shared-secret HMAC capability. */
    TicketVerifier verifier();
}
