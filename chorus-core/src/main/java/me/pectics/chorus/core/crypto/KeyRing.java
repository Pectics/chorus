package me.pectics.chorus.core.crypto;

import java.util.List;

/** Local signing and approved verification keys. Receiver-only nodes need no local signing key. */
public interface KeyRing {
    /** Throws IllegalStateException if this node has no signing key. */
    SigningKey activeSigningKey();

    /** Returns an immutable list of approved keys; unknown or disabled issuers are empty. */
    List<VerificationKey> findTrustedKeys(String issuerNodeId);
}
