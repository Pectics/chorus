package me.pectics.chorus.core.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.EdECKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/** Ed25519 signing pairs, public verification, and blocking DER/PEM loading for startup. */
public final class Ed25519 {
    private Ed25519() {}

    /**
     * A validated private/public pair. Rejects other algorithms and mismatched pairs with
     * IllegalArgumentException. The verifier contains only the public key and can be shared with
     * receivers. Each operation owns its JCA Signature instance.
     */
    public record Signer(PrivateKey privateKey, Verifier verifier) implements TicketSigner {
        public Signer {
            requireEd25519(privateKey);
            Objects.requireNonNull(verifier, "verifier");
            byte[] probe = "Chorus key pair validation".getBytes(StandardCharsets.US_ASCII);
            if (!verifier.verify(probe, Ed25519.sign(probe, privateKey))) {
                throw new IllegalArgumentException("Signing key pair does not match");
            }
        }

        public Signer(PrivateKey privateKey, PublicKey publicKey) {
            this(privateKey, new Verifier(publicKey));
        }

        @Override
        public SignatureAlgorithm algorithm() {
            return SignatureAlgorithm.ED25519;
        }

        @Override
        public byte[] sign(byte[] data) {
            return Ed25519.sign(Objects.requireNonNull(data, "data"), privateKey);
        }

        @Override
        public boolean verify(byte[] data, byte[] signature) {
            return verifier.verify(data, signature);
        }

        @Override
        public String toString() {
            return "Ed25519.Signer[redacted]";
        }
    }

    /** Public-key-only verification; rejects other key algorithms with IllegalArgumentException. */
    public record Verifier(PublicKey publicKey) implements TicketVerifier {
        public Verifier {
            requireEd25519(publicKey);
        }

        @Override
        public SignatureAlgorithm algorithm() {
            return SignatureAlgorithm.ED25519;
        }

        @Override
        public boolean verify(byte[] data, byte[] signature) {
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(signature, "signature");
            if (signature.length != 64) {
                return false;
            }
            try {
                var verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(publicKey);
                verifier.update(data);
                return verifier.verify(signature);
            } catch (SignatureException invalid) {
                return false;
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("Ed25519 verification failed", failure);
            }
        }
    }

    /** Loads PKCS#8 private and X.509 SPKI public files; never creates or replaces files. */
    public static Signer loadSigner(Path privateFile, Path publicFile)
            throws IOException, GeneralSecurityException {
        var factory = KeyFactory.getInstance("Ed25519");
        return new Signer(
                factory.generatePrivate(new PKCS8EncodedKeySpec(read(privateFile, "PRIVATE KEY"))),
                loadVerifier(publicFile));
    }

    /** Loads an X.509 SPKI public file without access to any private key. */
    public static Verifier loadVerifier(Path file) throws IOException, GeneralSecurityException {
        return new Verifier(
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(read(file, "PUBLIC KEY"))));
    }

    private static byte[] sign(byte[] data, PrivateKey key) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Ed25519 signing failed", failure);
        }
    }

    private static void requireEd25519(Key key) {
        if (!(key instanceof EdECKey ed) || !ed.getParams().getName().equals("Ed25519")) {
            throw new IllegalArgumentException("An Ed25519 key is required");
        }
    }

    private static byte[] read(Path file, String label) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(16385);
        }
        if (bytes.length > 16384) {
            throw new IOException("Key file exceeds size limit");
        }
        String pem = new String(bytes, StandardCharsets.US_ASCII).trim();
        if (!pem.startsWith("-----BEGIN")) {
            return bytes;
        }
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) {
            throw new IOException("Unsupported key file format");
        }
        try {
            return Base64.getDecoder()
                    .decode(
                            pem.substring(begin.length(), pem.length() - end.length())
                                    .replaceAll("\\s", ""));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid key encoding");
        }
    }
}
