/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.2 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/trust/TrustedIssuerConfiguration.java
 * sync: Keep approval values immutable and reject absent key paths before any key file is read.
 * @endcontext
 */
package me.pectics.chorus.core.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import org.junit.jupiter.api.Test;

class TrustedIssuerConfigurationTest {
    @Test
    void copiesApprovedKeyPathsAndKeepsAllPolicyFields() {
        var paths = new ArrayList<>(List.of(Path.of("keys/gateway.pub")));
        var configuration =
                new TrustedIssuerConfiguration(
                        "gateway", SignatureAlgorithm.ED25519, paths, false, TestSupport.policy());
        paths.clear();
        assertThat(configuration.keyPaths()).containsExactly(Path.of("keys/gateway.pub"));
        assertThat(configuration.algorithm()).isEqualTo(SignatureAlgorithm.ED25519);
        assertThat(configuration.enabled()).isFalse();
        assertThat(configuration.policy()).isEqualTo(TestSupport.policy());
        assertThatThrownBy(() -> configuration.keyPaths().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingIdentifiersAlgorithmsPoliciesAndKeyPaths() {
        var path = List.of(Path.of("keys/gateway.pub"));
        for (String invalid : new String[] {null, "", "bad id", ".gateway", "a".repeat(129)}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new TrustedIssuerConfiguration(
                                            invalid,
                                            SignatureAlgorithm.ED25519,
                                            path,
                                            true,
                                            TestSupport.policy()));
        }
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new TrustedIssuerConfiguration(
                                        "gateway", null, path, true, TestSupport.policy()));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new TrustedIssuerConfiguration(
                                        "gateway", SignatureAlgorithm.ED25519, path, true, null));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new TrustedIssuerConfiguration(
                                        "gateway",
                                        SignatureAlgorithm.ED25519,
                                        List.of(),
                                        true,
                                        TestSupport.policy()));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new TrustedIssuerConfiguration(
                                        "gateway",
                                        SignatureAlgorithm.ED25519,
                                        null,
                                        true,
                                        TestSupport.policy()));
    }
}
