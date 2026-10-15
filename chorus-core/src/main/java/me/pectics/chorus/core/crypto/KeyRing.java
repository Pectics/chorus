package me.pectics.chorus.core.crypto;

import java.util.Optional;

/** Local signing and approved verification keys. Receiver-only nodes need no private key. */
public interface KeyRing {
    /** Throws IllegalStateException if this node has no signing key. */
    SigningKey activeSigningKey();

    Optional<VerificationKey> findTrustedKey(String issuerNodeId, String kid);
}
