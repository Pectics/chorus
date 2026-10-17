/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.7 and 3.9
 * sync: Describe local signing paths without loading or generating key material in configuration models.
 * @endcontext
 */
package me.pectics.chorus.core.crypto;

import java.nio.file.Path;

/**
 * Local signing configuration for the two supported algorithms. Paths may be null for receiver-only
 * nodes; sender assembly requires all paths for its algorithm. File loading resolves paths relative
 * to the main configuration. These immutable values perform no I/O and contain no key material.
 */
public sealed interface TicketCryptoConfiguration {
    SignatureAlgorithm algorithm();

    /** Administrator-provided PKCS#8 private key and matching X.509 SPKI public key. */
    record Ed25519(Path privateKey, Path publicKey) implements TicketCryptoConfiguration {
        @Override
        public SignatureAlgorithm algorithm() {
            return SignatureAlgorithm.ED25519;
        }
    }

    /** Shared secret path; startup generates a missing file only for a sender role. */
    record HmacSha256(Path key) implements TicketCryptoConfiguration {
        @Override
        public SignatureAlgorithm algorithm() {
            return SignatureAlgorithm.HMAC_SHA256;
        }
    }
}
