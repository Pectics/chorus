/*
 * @context
 * refs: ARCHITECTURE.md, section 3.7
 * sync: Keep the capability hierarchy closed to Ed25519 and HMAC-SHA256.
 * @endcontext
 */
package me.pectics.chorus.core.crypto;

/**
 * Thread-safe, nonblocking ticket signature verification. Ed25519 verification needs only a public
 * key; HMAC verification uses the same secret as signing. Null inputs throw NullPointerException,
 * invalid signatures return false, and JCA infrastructure failures throw IllegalStateException.
 */
public sealed interface TicketVerifier permits Ed25519.Verifier, TicketSigner {
    SignatureAlgorithm algorithm();

    boolean verify(byte[] data, byte[] signature);
}
