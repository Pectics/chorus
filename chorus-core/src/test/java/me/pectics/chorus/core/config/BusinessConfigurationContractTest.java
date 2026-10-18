package me.pectics.chorus.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.identity.BuiltinIdentityResolutionPolicy;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuanceConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuancePolicy;
import me.pectics.chorus.core.ticket.TicketVerificationConfiguration;
import me.pectics.chorus.core.trust.IssuerTrustPolicy;
import me.pectics.chorus.core.trust.TrustedIssuerConfiguration;
import org.junit.jupiter.api.Test;

class BusinessConfigurationContractTest {
    @Test
    void issuerApprovalSettingsSnapshotCallerOwnedKeyPaths() {
        Path approvedKey = Path.of("keys/approved.key");
        var keyPaths = new ArrayList<>(List.of(approvedKey));
        var approval =
                new TrustedIssuerConfiguration(
                        "gateway",
                        SignatureAlgorithm.HMAC_SHA256,
                        keyPaths,
                        true,
                        new IssuerTrustPolicy(
                                Set.of(AuthenticationSourceType.MOJANG), Set.of("mojang")));
        keyPaths.clear();
        keyPaths.add(Path.of("keys/unapproved.key"));
        assertThat(approval.keyPaths()).containsExactly(approvedKey);
    }

    @Test
    void localReplayLimitsAreValidatedBeforeOpeningTheDatabase() {
        for (var timeout :
                java.util.List.of(
                        Duration.ZERO,
                        Duration.ofNanos(1),
                        Duration.ofSeconds(-1),
                        Duration.ofMillis((long) Integer.MAX_VALUE + 1))) {
            assertThatThrownBy(
                            () ->
                                    new ReplayGuardConfiguration.Local(
                                            Path.of("state/replay.db"), timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ticketPropertiesCannotExceedTheCompleteCookieBudget() {
        assertThatThrownBy(
                        () ->
                                new TicketConfiguration(
                                        "chorus:ticket", 4096, 4097, Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TicketConfiguration(
                                        "chorus:ticket", 5121, 2048, Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ticketLifetimeAndIssuanceTtlAreValidated() {
        var policy =
                new TicketIssuancePolicy(
                        new IssuerTrustPolicy(
                                Set.of(AuthenticationSourceType.MOJANG), Set.of("mojang")),
                        Set.of("backend"));
        for (var duration :
                java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500))) {
            assertThatThrownBy(() -> new TicketIssuanceConfiguration(duration, policy))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TicketConfiguration("chorus:ticket", 4096, 2048, duration))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                new TicketConfiguration(
                                        "chorus:ticket",
                                        4096,
                                        2048,
                                        Duration.ofSeconds(Long.MAX_VALUE / 1000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new TicketVerificationConfiguration(
                                true, BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM))
                .isNotNull();
    }
}
