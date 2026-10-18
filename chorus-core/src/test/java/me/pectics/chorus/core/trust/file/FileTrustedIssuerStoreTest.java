/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.2, and 3.7
 * related: chorus-core/src/main/java/me/pectics/chorus/core/trust/file/FileTrustedIssuerStore.java
 * sync: Keep local approval replacement atomic and independent from registry snapshots.
 * @endcontext
 */
package me.pectics.chorus.core.trust.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.trust.TrustedIssuer;
import org.junit.jupiter.api.Test;

class FileTrustedIssuerStoreTest {
    private final java.security.KeyPair pair = TestSupport.keyPair();

    private TrustedIssuer approval(String issuer) {
        return new TrustedIssuer(
                issuer,
                List.of(new Ed25519.Verifier(pair.getPublic())),
                true,
                TestSupport.policy());
    }

    @Test
    void publishesApprovalsAtomicallyAndReturnsEmptyForUnknownIssuers() {
        var gateway = approval("gateway");
        var store = new FileTrustedIssuerStore(List.of(gateway));
        assertThat(store.findByIssuer("gateway")).containsSame(gateway);
        assertThat(store.findByIssuer("outsider")).isEmpty();

        var backend = approval("backend");
        store.replace(List.of(backend));
        assertThat(store.findByIssuer("gateway")).isEmpty();
        assertThat(store.findByIssuer("backend")).containsSame(backend);
    }

    @Test
    void duplicateOrMissingApprovalsLeaveThePreviousSnapshotIntact() {
        var gateway = approval("gateway");
        var store = new FileTrustedIssuerStore(List.of(gateway));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new FileTrustedIssuerStore(List.of(gateway, approval("gateway"))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.replace(List.of(approval("other"), approval("other"))));
        assertThat(store.findByIssuer("gateway")).containsSame(gateway);
        assertThat(store.findByIssuer("other")).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> store.replace(null));
        assertThat(store.findByIssuer("gateway")).containsSame(gateway);
    }
}
