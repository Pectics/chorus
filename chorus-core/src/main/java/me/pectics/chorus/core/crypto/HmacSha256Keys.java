/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.7 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/CoreBootstrap.java
 * sync: Generate only local signing secrets; trust loading must never create missing keys.
 * @endcontext
 */
package me.pectics.chorus.core.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Set;

/**
 * Blocking startup I/O for raw 32-byte shared secrets; invalid existing files are never replaced.
 */
public final class HmacSha256Keys {
    private HmacSha256Keys() {}

    /**
     * Loads an existing secret without creating files. Missing or invalid files throw IOException.
     */
    public static HmacSha256Key load(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(HmacSha256Key.BYTES + 1);
        }
        if (bytes.length != HmacSha256Key.BYTES) {
            throw new IOException("HMAC-SHA256 key file must contain exactly 32 raw bytes");
        }
        return new HmacSha256Key(bytes);
    }

    /**
     * Generates a secret and parent directories only when the file is missing. Uses exclusive
     * creation, owner-only POSIX permissions when available, and forces file contents before
     * return. An existing file is never truncated. I/O failures, including incomplete concurrent
     * writes, fail closed; a failed write may leave an invalid file requiring administrator repair.
     */
    public static HmacSha256Key loadOrCreate(Path file) throws IOException {
        try {
            return load(file);
        } catch (NoSuchFileException missing) {
            Path path = file.toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            byte[] bytes = new byte[HmacSha256Key.BYTES];
            new SecureRandom().nextBytes(bytes);
            FileAttribute<?>[] attributes =
                    Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")
                            ? new FileAttribute<?>[] {
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------"))
                            }
                            : new FileAttribute<?>[0];
            try (var channel =
                    FileChannel.open(
                            path,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                            attributes)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            } catch (FileAlreadyExistsException concurrentlyCreated) {
                return load(path);
            }
            return new HmacSha256Key(bytes);
        }
    }
}
