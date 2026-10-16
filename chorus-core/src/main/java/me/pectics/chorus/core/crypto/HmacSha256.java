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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A shared 256-bit secret with signing and verification capabilities. Copies constructor bytes and
 * never exposes secret material. Invalid lengths throw IllegalArgumentException. Every holder can
 * sign, so distribute only within a mutually trusted group. Each operation owns its JCA Mac.
 */
public final class HmacSha256 implements TicketSigner {
    private static final int KEY_BYTES = 32;
    private final SecretKeySpec key;

    public HmacSha256(byte[] bytes) {
        if (bytes == null || bytes.length != KEY_BYTES) {
            throw new IllegalArgumentException("A 256-bit HmacSHA256 key is required");
        }
        key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Override
    public SignatureAlgorithm algorithm() {
        return SignatureAlgorithm.HMAC_SHA256;
    }

    @Override
    public byte[] sign(byte[] data) {
        Objects.requireNonNull(data, "data");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 operation failed", failure);
        }
    }

    @Override
    public boolean verify(byte[] data, byte[] signature) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(signature, "signature");
        return signature.length == 32 && MessageDigest.isEqual(sign(data), signature);
    }

    /** Blocking startup I/O; missing or invalid files throw IOException without creating files. */
    public static HmacSha256 load(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(KEY_BYTES + 1);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IOException("HMAC-SHA256 key file must contain exactly 32 raw bytes");
        }
        return new HmacSha256(bytes);
    }

    /**
     * Blocking startup I/O. Generates a secret and parent directories only when missing, using
     * exclusive creation, owner-only POSIX permissions when available, and forcing file contents
     * before return. Existing files are never truncated. I/O failures, including incomplete
     * concurrent writes, fail closed; a failed write may leave an invalid file requiring repair.
     */
    public static HmacSha256 loadOrCreate(Path file) throws IOException {
        try {
            return load(file);
        } catch (NoSuchFileException missing) {
            Path path = file.toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            byte[] bytes = new byte[KEY_BYTES];
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
            return new HmacSha256(bytes);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HmacSha256 hmac && key.equals(hmac.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "HmacSha256[redacted]";
    }
}
