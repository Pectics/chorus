package me.pectics.chorus.core.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Loads administrator-provided PKCS#8 private and X.509 SPKI public keys in DER or PEM form. */
public final class Ed25519Keys {
    private Ed25519Keys() {}

    public static SigningKey load(Path privateFile, Path publicFile)
            throws IOException, GeneralSecurityException {
        var factory = KeyFactory.getInstance("Ed25519");
        return new SigningKey(
                factory.generatePrivate(new PKCS8EncodedKeySpec(read(privateFile, "PRIVATE KEY"))),
                loadPublic(publicFile));
    }

    public static PublicKey loadPublic(Path file) throws IOException, GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(read(file, "PUBLIC KEY")));
    }

    static void requireEd25519(Key key) {
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
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid key encoding");
        }
    }
}
