/*
 * @context
 * refs: ARCHITECTURE.md, section 3.4
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketCompression.java
 * sync: Exercise the exact frame-length and decompression limits at their boundaries.
 * @endcontext
 */
package me.pectics.chorus.core.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import org.junit.jupiter.api.Test;

class TicketCompressionBoundsTest {
    private static final int MAGIC = 0x43485253;

    private static void rejects(byte[] payload, TicketError error) {
        assertThatThrownBy(() -> TicketCompression.decode(payload))
                .isInstanceOfSatisfying(
                        TicketRejectedException.class,
                        rejection -> assertThat(rejection.code()).isEqualTo(error));
    }

    @Test
    void acceptsExactlyTheDecompressedLimitAndRejectsOneByteMore() {
        byte[] maximum = new byte[TicketCompression.MAX_DECOMPRESSED_BYTES];
        Arrays.fill(maximum, (byte) 'a');
        assertThat(TicketCompression.NONE.encode(maximum)).hasSize(maximum.length);
        assertThat(TicketCompression.ZSTD.encode(maximum).length).isGreaterThan(10);
        assertThat(TicketCompression.GZIP.encode(maximum).length).isGreaterThan(10);

        byte[] oversized = new byte[TicketCompression.MAX_DECOMPRESSED_BYTES + 1];
        Arrays.fill(oversized, (byte) 'a');
        for (var compression : TicketCompression.values()) {
            assertThatThrownBy(() -> compression.encode(oversized))
                    .isInstanceOfSatisfying(
                            TicketRejectedException.class,
                            rejection ->
                                    assertThat(rejection.code())
                                            .isEqualTo(TicketError.TICKET_TOO_LARGE));
        }
    }

    @Test
    void treatsNonFramesAsPlainJwsButRejectsEveryTruncatedMagicPrefix() {
        byte[] plain = "a.b.c".getBytes(StandardCharsets.US_ASCII);
        assertThat(TicketCompression.decode(plain)).isSameAs(plain);
        assertThat(TicketCompression.decode(new byte[] {1, 2, 3})).hasSize(3);

        byte[] header =
                ByteBuffer.allocate(10).putInt(MAGIC).put((byte) 1).put((byte) 1).putInt(3).array();
        for (int length = 4; length <= 10; length++) {
            rejects(Arrays.copyOf(header, length), TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void enforcesDeclaredLengthBoundariesBeforeDecompression() {
        for (byte algorithm : new byte[] {1, 2}) {
            for (int length : new int[] {0, -1}) {
                rejects(frame(algorithm, length), TicketError.MALFORMED_TICKET);
            }
            rejects(
                    frame(algorithm, TicketCompression.MAX_DECOMPRESSED_BYTES + 1),
                    TicketError.TICKET_TOO_LARGE);
            // The exact limit is accepted by the size check and then fails as truncated data.
            rejects(
                    frame(algorithm, TicketCompression.MAX_DECOMPRESSED_BYTES),
                    TicketError.MALFORMED_TICKET);
        }
    }

    @Test
    void roundTripsFramesWhoseDeclaredLengthEqualsTheDecompressedSize() {
        for (var compression :
                new TicketCompression[] {TicketCompression.ZSTD, TicketCompression.GZIP}) {
            byte[] compact = "header.payload.signature".getBytes(StandardCharsets.US_ASCII);
            byte[] frame = compression.encode(compact);
            assertThat(ByteBuffer.wrap(frame).getInt(6)).isEqualTo(compact.length);
            assertThat(TicketCompression.decode(frame)).isEqualTo(compact);

            byte[] wrongLength = frame.clone();
            ByteBuffer.wrap(wrongLength).putInt(6, compact.length + 1);
            rejects(wrongLength, TicketError.MALFORMED_TICKET);
            wrongLength = frame.clone();
            ByteBuffer.wrap(wrongLength).putInt(6, compact.length - 1);
            rejects(wrongLength, TicketError.MALFORMED_TICKET);
        }
    }

    private static byte[] frame(byte algorithm, int length) {
        return ByteBuffer.allocate(11)
                .putInt(MAGIC)
                .put((byte) 1)
                .put(algorithm)
                .putInt(length)
                .put((byte) 0)
                .array();
    }
}
