package me.pectics.chorus.core.config.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.policy.BuiltinIdentityResolutionPolicy;
import me.pectics.chorus.core.policy.IssuancePolicy;
import me.pectics.chorus.core.policy.IssuerTrustPolicy;
import org.junit.jupiter.api.Test;

class ConfigurationModelsTest {
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
                                            Path.of("state/replay.db"), 100, timeout))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Local(
                                        Path.of("state/replay.db"), 0, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ticketPropertiesCannotExceedTheCompleteCookieBudget() {
        assertThatThrownBy(() -> new TicketConfiguration("chorus:ticket", 4096, 4097))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TicketConfiguration("chorus:ticket", 5121, 2048))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuanceAndVerificationEnforceTheirOwnTimeInvariants() {
        var policy =
                new IssuancePolicy(
                        new IssuerTrustPolicy(Set.of(AuthType.MOJANG), Set.of("mojang")),
                        Set.of("backend"));
        for (var duration :
                java.util.List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500))) {
            assertThatThrownBy(() -> new IssuanceConfiguration(duration, policy))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    new VerificationConfiguration(
                                            duration,
                                            Duration.ZERO,
                                            true,
                                            BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                new VerificationConfiguration(
                                        Duration.ofSeconds(15),
                                        Duration.ofMillis(500),
                                        true,
                                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new VerificationConfiguration(
                                        Duration.ofSeconds(Long.MAX_VALUE / 1000),
                                        Duration.ofSeconds(1),
                                        true,
                                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM))
                .isInstanceOf(ArithmeticException.class);
    }
}
