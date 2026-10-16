package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import me.pectics.chorus.core.crypto.HmacSha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacSha256FileTest {
    @TempDir Path directory;

    @Test
    void generatesMissingSecretWithRestrictedPermissionsAndReusesIt() throws Exception {
        Path file = directory.resolve("keys/shared.key");
        var first = HmacSha256.loadOrCreate(file);
        byte[] saved = Files.readAllBytes(file);
        assertThat(saved).hasSize(32);
        assertThat(new HmacSha256(saved)).isEqualTo(first);
        assertThat(HmacSha256.loadOrCreate(file)).isEqualTo(first);
        assertThat(HmacSha256.load(file)).isEqualTo(first);
        assertThat(Files.readAllBytes(file)).isEqualTo(saved);
        assertThat(HmacSha256.loadOrCreate(directory.resolve("other.key"))).isNotEqualTo(first);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void rejectsExistingInvalidSecretsWithoutOverwritingThem() throws Exception {
        Path file = directory.resolve("shared.key");
        for (int length : new int[] {0, 31, 33, 16385}) {
            byte[] invalid = new byte[length];
            Files.write(file, invalid);
            assertThatThrownBy(() -> HmacSha256.loadOrCreate(file)).isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(file)).isEqualTo(invalid);
        }
        assertThatThrownBy(() -> HmacSha256.loadOrCreate(directory))
                .isInstanceOf(IOException.class);
    }

    @Test
    void loadingAMissingTrustSecretNeverCreatesItOrItsParentDirectory() {
        Path file = directory.resolve("unsynchronized/shared.key");
        assertThatThrownBy(() -> HmacSha256.load(file)).isInstanceOf(IOException.class);
        assertThat(file.getParent()).doesNotExist();
    }
}
