/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.4 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/ticket/TicketCompression.java
 * sync: Exercise framing compatibility, bounded decoding, and signature checks for every encoding.
 * @endcontext
 */
package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.luben.zstd.Zstd;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.identity.IdentityClaim;
import me.pectics.chorus.core.identity.ProfileProperty;
import me.pectics.chorus.core.ticket.TicketClaims;
import me.pectics.chorus.core.ticket.TicketCodec;
import me.pectics.chorus.core.ticket.TicketCompression;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import me.pectics.chorus.core.trust.TrustedIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TicketCompressionTest {
    private final java.security.KeyPair pair = TestSupport.keyPair();
    private final TicketSigner signer = TestSupport.signer(pair);
    private final TicketCodec plain = new TicketCodec(5120, 5120, TicketCompression.NONE);
    private final me.pectics.chorus.core.trust.file.FileTrustedIssuerStore issuers =
            TestSupport.issuers(pair);

    private void rejects(byte[] payload, TicketError error) {
        assertThatThrownBy(() -> plain.decode(payload, issuers))
                .isInstanceOfSatisfying(
                        TicketRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(error));
    }

    private byte[] frame(byte[] compact, TicketCompression compression) throws Exception {
        byte[] compressed;
        if (compression == TicketCompression.ZSTD) {
            compressed = Zstd.compress(compact);
        } else {
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(compact);
            }
            compressed = output.toByteArray();
        }
        return ByteBuffer.allocate(10 + compressed.length)
                .put(new byte[] {'C', 'H', 'R', 'S', 1})
                .put((byte) (compression == TicketCompression.ZSTD ? 1 : 2))
                .putInt(compact.length)
                .put(compressed)
                .array();
    }

    @Test
    void zstdIsTheDefaultForTheCodecAndProgrammaticConfiguration() {
        assertThat(new TicketCodec(5120, 2048).encode(TestSupport.claims(), signer))
                .isEqualTo(
                        new TicketCodec(5120, 2048, TicketCompression.ZSTD)
                                .encode(TestSupport.claims(), signer));
        assertThat(
                        new TicketConfiguration("chorus:ticket", 4096, 2048, Duration.ofSeconds(15))
                                .compression())
                .isEqualTo(TicketCompression.ZSTD);
    }

    @ParameterizedTest
    @EnumSource(TicketCompression.class)
    void allReceiversAcceptEveryEncodingWithBothSigningAlgorithms(TicketCompression compression) {
        for (TicketSigner key : List.of(signer, new HmacSha256(new byte[32]))) {
            issuers.replace(
                    List.of(
                            new TrustedIssuer(
                                    "gateway", List.of(key), true, TestSupport.policy())));
            byte[] payload =
                    new TicketCodec(5120, 2048, compression).encode(TestSupport.claims(), key);
            for (var receiver : TicketCompression.values()) {
                assertThat(new TicketCodec(5120, 2048, receiver).decode(payload, issuers).claims())
                        .isEqualTo(TestSupport.claims());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketCompression.class,
            names = {"ZSTD", "GZIP"})
    void usesDocumentedFrameAroundAnUnmodifiedSignedJws(TicketCompression compression)
            throws Exception {
        byte[] compact = plain.encode(TestSupport.claims(), signer);
        byte[] payload =
                new TicketCodec(5120, 5120, compression).encode(TestSupport.claims(), signer);
        assertThat(Arrays.copyOf(payload, 5)).containsExactly('C', 'H', 'R', 'S', 1);
        assertThat(payload[5]).isEqualTo((byte) (compression == TicketCompression.ZSTD ? 1 : 2));
        assertThat(ByteBuffer.wrap(payload).getInt(6)).isEqualTo(compact.length);
        byte[] compressed = Arrays.copyOfRange(payload, 10, payload.length);
        byte[] decompressed;
        if (compression == TicketCompression.ZSTD) {
            decompressed = Zstd.decompress(compressed, compact.length);
        } else {
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                decompressed = gzip.readAllBytes();
            }
        }
        assertThat(decompressed).isEqualTo(compact);
        assertThat(plain.decode(frame(compact, compression), issuers).claims())
                .isEqualTo(TestSupport.claims());
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketCompression.class,
            names = {"ZSTD", "GZIP"})
    void limitsFinalCookieBytesAndKeepsThePropertyBudget(TicketCompression compression) {
        var identity = TestSupport.identity();
        var claims =
                new TicketClaims(
                        1,
                        "large-property",
                        "gateway",
                        "backend",
                        TestSupport.NOW,
                        TestSupport.NOW,
                        TestSupport.NOW.plusSeconds(15),
                        new IdentityClaim(
                                identity.username(),
                                identity.authType(),
                                identity.authProvider(),
                                identity.providerSubject(),
                                identity.sourceUuid(),
                                List.of(new ProfileProperty("textures", "皮".repeat(1600), null))),
                        Map.of());
        var codec = new TicketCodec(5120, 5120, compression);
        byte[] payload = codec.encode(claims, signer);
        assertThat(payload.length).isLessThan(5120);
        assertThat(ByteBuffer.wrap(payload).getInt(6)).isGreaterThan(5120);
        assertThat(codec.decode(payload, issuers).claims()).isEqualTo(claims);
        assertThatThrownBy(() -> plain.encode(claims, signer)).hasMessage("TICKET_TOO_LARGE");
        assertThatThrownBy(() -> new TicketCodec(5120, 2048, compression).encode(claims, signer))
                .hasMessage("TICKET_TOO_LARGE");
        assertThatThrownBy(() -> new TicketCodec(5120, 2048, compression).decode(payload, issuers))
                .hasMessage("TICKET_TOO_LARGE");

        byte[] small = codec.encode(TestSupport.claims(), signer);
        assertThat(
                        new TicketCodec(small.length, 128, compression)
                                .encode(TestSupport.claims(), signer))
                .hasSize(small.length);
        assertThatThrownBy(
                        () ->
                                new TicketCodec(small.length - 1, 128, compression)
                                        .encode(TestSupport.claims(), signer))
                .hasMessage("TICKET_TOO_LARGE");
        assertThatThrownBy(
                        () ->
                                new TicketCodec(small.length - 1, 128, compression)
                                        .decode(small, issuers))
                .hasMessage("TICKET_TOO_LARGE");
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketCompression.class,
            names = {"ZSTD", "GZIP"})
    void rejectsTruncatedFramesUnknownVersionsAndInvalidMetadata(TicketCompression compression) {
        byte[] payload =
                new TicketCodec(5120, 2048, compression).encode(TestSupport.claims(), signer);
        for (int length = 1; length < payload.length; length++) {
            rejects(Arrays.copyOf(payload, length), TicketError.MALFORMED_TICKET);
        }
        byte[] changed = payload.clone();
        changed[4] = 2;
        rejects(changed, TicketError.UNSUPPORTED_VERSION);
        for (int algorithm : List.of(0, 3, 255)) {
            changed = payload.clone();
            changed[5] = (byte) algorithm;
            rejects(changed, TicketError.MALFORMED_TICKET);
        }
        for (int length : List.of(-1, 0, 1, 65_536)) {
            changed = payload.clone();
            ByteBuffer.wrap(changed).putInt(6, length);
            rejects(changed, TicketError.MALFORMED_TICKET);
        }
        changed = payload.clone();
        ByteBuffer.wrap(changed).putInt(6, 65_537);
        rejects(changed, TicketError.TICKET_TOO_LARGE);
        changed = payload.clone();
        changed[10] ^= 0x7f;
        rejects(changed, TicketError.MALFORMED_TICKET);
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketCompression.class,
            names = {"ZSTD", "GZIP"})
    void rejectsDecompressionBombsAndNestedFrames(TicketCompression compression) throws Exception {
        byte[] bomb = frame("x".repeat(1_000_000).getBytes(StandardCharsets.US_ASCII), compression);
        assertThat(bomb.length).isLessThan(5120);
        rejects(bomb, TicketError.TICKET_TOO_LARGE);
        ByteBuffer.wrap(bomb).putInt(6, 64);
        rejects(bomb, TicketError.MALFORMED_TICKET);
        ByteBuffer.wrap(bomb).putInt(6, 65_536);
        rejects(bomb, TicketError.MALFORMED_TICKET);
        rejects(
                frame(frame(plain.encode(TestSupport.claims(), signer), compression), compression),
                TicketError.MALFORMED_TICKET);
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketCompression.class,
            names = {"ZSTD", "GZIP"})
    void verifiesSignaturesAndStrictClaimsAfterDecompression(TicketCompression compression)
            throws Exception {
        byte[] compact = plain.encode(TestSupport.claims(), signer);
        String[] parts = new String(compact, StandardCharsets.US_ASCII).split("\\.");
        String header =
                new String(
                        java.util.Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String body =
                new String(
                        java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        rejects(
                frame(TestSupport.signRaw(header, body, TestSupport.keyPair()), compression),
                TicketError.INVALID_SIGNATURE);
        rejects(
                frame(
                        TestSupport.signRaw(
                                header, body.replace("\"version\":1", "\"version\":2"), pair),
                        compression),
                TicketError.UNSUPPORTED_VERSION);
        rejects(
                frame(
                        TestSupport.signRaw(
                                header,
                                body.replace("\"version\":1", "\"version\":1,\"version\":1"),
                                pair),
                        compression),
                TicketError.MALFORMED_TICKET);
        rejects(frame(new byte[] {(byte) 0xff}, compression), TicketError.MALFORMED_TICKET);
    }
}
