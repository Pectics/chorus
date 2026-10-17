package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.crypto.TicketVerifier;
import me.pectics.chorus.core.trust.TrustedIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TicketCryptoTest {
    static Stream<TicketSigner> signers() {
        var pair = TestSupport.keyPair();
        return Stream.of(
                new Ed25519.Signer(pair.getPrivate(), pair.getPublic()),
                new HmacSha256(new byte[32]));
    }

    @ParameterizedTest
    @MethodSource("signers")
    void signingIncludesVerificationAndRejectsTampering(TicketSigner signer) {
        byte[] message = "ticket payload".getBytes(StandardCharsets.US_ASCII);
        byte[] signature = signer.sign(message);
        TicketVerifier verifier = signer;
        assertThat(verifier.verify(message, signature)).isTrue();
        assertThat(signer.verifier().verify(message, signature)).isTrue();
        assertThat(verifier.verify("changed".getBytes(StandardCharsets.US_ASCII), signature))
                .isFalse();
        assertThat(verifier.verify(message, Arrays.copyOf(signature, signature.length - 1)))
                .isFalse();
        assertThat(verifier.verify(message, Arrays.copyOf(signature, signature.length + 1)))
                .isFalse();
        signature[0] ^= 1;
        assertThat(verifier.verify(message, signature)).isFalse();
        assertThatThrownBy(() -> signer.sign(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> verifier.verify(null, signature))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> verifier.verify(message, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ed25519ExposesAPublicVerifierAndRejectsMismatchedPairs() {
        var pair = TestSupport.keyPair();
        var signer = new Ed25519.Signer(pair.getPrivate(), pair.getPublic());
        byte[] message = new byte[] {1, 2, 3};
        assertThat(signer.verifier().verify(message, signer.sign(message))).isTrue();
        assertThat(signer.verifier()).isNotInstanceOf(TicketSigner.class);
        assertThatThrownBy(
                        () ->
                                new Ed25519.Signer(
                                        pair.getPrivate(), TestSupport.keyPair().getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(signer.toString()).isEqualTo("Ed25519.Signer[redacted]");
    }

    @Test
    void hmacCopiesSecretInputAndProvidesValueEqualityWithoutExposingSecrets() {
        byte[] secret = new byte[32];
        var first = new HmacSha256(secret);
        var same = new HmacSha256(secret);
        secret[0] = 1;
        var different = new HmacSha256(secret);
        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(different);
        byte[] message = new byte[] {1};
        assertThat(same.verify(message, first.sign(message))).isTrue();
        assertThat(different.verify(message, first.sign(message))).isFalse();
        assertThat(first.toString()).isEqualTo("HmacSha256[redacted]");
        assertThat(first.verifier()).isSameAs(first);
        assertThatThrownBy(() -> new HmacSha256(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalKeepsOnlyPublicEd25519VerificationAndRejectsDuplicateViews() {
        var pair = TestSupport.keyPair();
        var signer = new Ed25519.Signer(pair.getPrivate(), pair.getPublic());
        var approval = new TrustedIssuer("gateway", List.of(signer), true, TestSupport.policy());
        assertThat(approval.verifiers()).containsExactly(signer.verifier());
        assertThat(approval.verifiers().getFirst()).isNotInstanceOf(TicketSigner.class);
        assertThatThrownBy(
                        () ->
                                new TrustedIssuer(
                                        "gateway",
                                        List.of(signer, signer.verifier()),
                                        true,
                                        TestSupport.policy()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct approved keys");
    }
}
