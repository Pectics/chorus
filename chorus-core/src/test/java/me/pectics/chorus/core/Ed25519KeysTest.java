package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import me.pectics.chorus.core.crypto.Ed25519Keys;
import me.pectics.chorus.core.crypto.SigningKey;
import me.pectics.chorus.core.crypto.VerificationKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Ed25519KeysTest {
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
        assertThat(Ed25519Keys.load("key-1", privateFile, publicFile).publicKey())
                .isEqualTo(pair.getPublic());
        Files.write(publicFile, TestSupport.keyPair().getPublic().getEncoded());
        assertThatThrownBy(() -> Ed25519Keys.load("key-1", privateFile, publicFile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEd448AndInvalidKeyFiles() throws Exception {
        var ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        assertThatThrownBy(() -> new VerificationKey("key", ed448.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        Path file = directory.resolve("broken.key");
        Files.writeString(file, "not a key");
        assertThatThrownBy(() -> Ed25519Keys.loadPublic(file)).isInstanceOf(Exception.class);
        var pair = TestSupport.keyPair();
        assertThat(new SigningKey("key", pair.getPrivate(), pair.getPublic()).toString())
                .isEqualTo("SigningKey[keyId=key]");
    }
}
