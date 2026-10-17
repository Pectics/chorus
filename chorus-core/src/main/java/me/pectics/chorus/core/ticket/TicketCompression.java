/*
 * @context
 * refs: ARCHITECTURE.md, section 3.4
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketCodec.java
 * sync: Preserve legacy JWS compatibility and bound decompression before parsing untrusted claims.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;

/** Outgoing ticket encoding; receivers accept all three encodings independently of this choice. */
public enum TicketCompression {
    NONE(0),
    ZSTD(1),
    GZIP(2);

    static final int MAX_DECOMPRESSED_BYTES = 65_536;
    private static final int MAGIC =
            0x43485253; // ASCII CHRS, outside a valid JWS JSON header prefix.
    private static final int HEADER_BYTES = 10;
    private final int wireId;

    TicketCompression(int wireId) {
        this.wireId = wireId;
    }

    byte[] encode(byte[] compact) {
        if (compact.length > MAX_DECOMPRESSED_BYTES) {
            throw reject(TicketError.TICKET_TOO_LARGE);
        }
        if (this == NONE) {
            return compact;
        }
        byte[] compressed;
        if (this == ZSTD) {
            compressed = Zstd.compress(compact);
        } else {
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(compact);
            } catch (IOException failure) {
                throw new IllegalStateException("Ticket compression failed", failure);
            }
            compressed = output.toByteArray();
        }
        return ByteBuffer.allocate(HEADER_BYTES + compressed.length)
                .putInt(MAGIC)
                .put((byte) 1)
                .put((byte) wireId)
                .putInt(compact.length)
                .put(compressed)
                .array();
    }

    static byte[] decode(byte[] payload) {
        var frame = ByteBuffer.wrap(payload);
        if (payload.length < 4 || frame.getInt() != MAGIC) {
            return payload;
        }
        if (payload.length <= HEADER_BYTES) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
        if (frame.get() != 1) {
            throw reject(TicketError.UNSUPPORTED_VERSION);
        }
        int algorithm = Byte.toUnsignedInt(frame.get());
        if (algorithm != ZSTD.wireId && algorithm != GZIP.wireId) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
        int length = frame.getInt();
        if (length > MAX_DECOMPRESSED_BYTES) {
            throw reject(TicketError.TICKET_TOO_LARGE);
        }
        if (length < 1) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
        try {
            byte[] compact;
            if (algorithm == ZSTD.wireId) {
                // One-shot decoding writes only into the bounded destination; no streaming window.
                try (var zstd = new ZstdDecompressCtx()) {
                    compact =
                            zstd.decompress(
                                    payload, HEADER_BYTES, payload.length - HEADER_BYTES, length);
                }
            } else {
                try (var gzip =
                        new GZIPInputStream(
                                new ByteArrayInputStream(
                                        payload, HEADER_BYTES, payload.length - HEADER_BYTES))) {
                    compact = gzip.readNBytes(length + 1);
                }
            }
            if (compact.length != length) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            return compact;
        } catch (IOException | ZstdException failure) {
            throw reject(TicketError.MALFORMED_TICKET);
        }
    }

    private static TicketRejectedException reject(TicketError error) {
        return new TicketRejectedException(error);
    }
}
