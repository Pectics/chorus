package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import me.pectics.chorus.core.crypto.Ed25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Ed25519Test {
    @TempDir Path directory;

    @Test
    void loadsDerAndPemAndRejectsMismatchedKeys() throws Exception {
        var pair = TestSupport.keyPair();
        Path privateFile = directory.resolve("private.pem");
        Path publicFile = directory.resolve("public.der");
        Files.writeString(
                privateFile,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n");
        Files.write(publicFile, pair.getPublic().getEncoded());
        assertThat(Ed25519.loadSigner(privateFile, publicFile).verifier().publicKey())
                .isEqualTo(pair.getPublic());
        Files.write(publicFile, TestSupport.keyPair().getPublic().getEncoded());
        assertThatThrownBy(() -> Ed25519.loadSigner(privateFile, publicFile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadsPemPublicKeysAndNeverCreatesMissingSigningFiles() throws Exception {
        var pair = TestSupport.keyPair();
        Path publicPem = directory.resolve("public.pem");
        Files.writeString(
                publicPem,
                "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n");
        assertThat(Ed25519.loadVerifier(publicPem).publicKey()).isEqualTo(pair.getPublic());

        Path missingPrivate = directory.resolve("missing-private.pem");
        Path missingPublic = directory.resolve("missing-public.pem");
        assertThatThrownBy(() -> Ed25519.loadSigner(missingPrivate, missingPublic))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> Ed25519.loadVerifier(missingPublic))
                .isInstanceOf(IOException.class);
        assertThat(missingPrivate).doesNotExist();
        assertThat(missingPublic).doesNotExist();
    }

    @Test
    void rejectsEd448AndInvalidKeyFiles() throws Exception {
        assertThatThrownBy(() -> new Ed25519.Verifier(null))
                .isInstanceOf(IllegalArgumentException.class);
        var ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        assertThatThrownBy(() -> new Ed25519.Verifier(ed448.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ed25519.Signer(ed448.getPrivate(), ed448.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        Path file = directory.resolve("broken.key");
        Files.writeString(file, "not a key");
        assertThatThrownBy(() -> Ed25519.loadVerifier(file)).isInstanceOf(Exception.class);
        var pair = TestSupport.keyPair();
        assertThat(new Ed25519.Signer(pair.getPrivate(), pair.getPublic()).toString())
                .isEqualTo("Ed25519.Signer[redacted]");
    }

    @Test
    void enforcesKeyFileSizeAndPemLabelBoundaries() throws Exception {
        Path binary = directory.resolve("binary.bin");
        Files.write(binary, new byte[16_384]);
        assertThatThrownBy(() -> Ed25519.loadVerifier(binary))
                .isInstanceOf(java.security.GeneralSecurityException.class);
        Files.write(binary, new byte[16_385]);
        assertThatThrownBy(() -> Ed25519.loadVerifier(binary))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size limit");

        Path pem = directory.resolve("wrong-label.pem");
        Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");
        assertThatThrownBy(() -> Ed25519.loadVerifier(pem))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported key file format");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");
        assertThatThrownBy(() -> Ed25519.loadVerifier(pem))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsupported key file format");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n!!!!\n-----END PUBLIC KEY-----\n");
        assertThatThrownBy(() -> Ed25519.loadVerifier(pem))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid key encoding");
    }
}
