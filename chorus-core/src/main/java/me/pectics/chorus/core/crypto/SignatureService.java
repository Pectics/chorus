package me.pectics.chorus.core.crypto;

/** Thread-safe, nonblocking signature operations; invalid signatures return false. */
public interface SignatureService {
    byte[] sign(byte[] data, SigningKey key);

    boolean verify(byte[] data, byte[] signature, VerificationKey key);
}
