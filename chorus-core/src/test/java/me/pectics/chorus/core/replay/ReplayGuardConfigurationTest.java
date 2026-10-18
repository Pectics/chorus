/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuardConfiguration.java
 * sync: Cover every backend, endpoint, TLS, timeout, pool, and local boundary deterministically.
 * @endcontext
 */
package me.pectics.chorus.core.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayGuardConfigurationTest {
    @TempDir Path directory;

    @Test
    void localRequiresPathAndBoundedWholeMillisecondTimeout() {
        var path = directory.resolve("replay.db");
        assertThat(new ReplayGuardConfiguration.Local(path, Duration.ofMillis(1)).busyTimeout())
                .isEqualTo(Duration.ofMillis(1));
        assertThat(
                        new ReplayGuardConfiguration.Local(
                                        path, Duration.ofMillis(Integer.MAX_VALUE))
                                .busyTimeout())
                .isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
        for (Duration invalid :
                java.util.List.of(
                        Duration.ZERO,
                        Duration.ofNanos(1),
                        Duration.ofMillis(-1),
                        Duration.ofMillis((long) Integer.MAX_VALUE + 1),
                        Duration.ofMillis(Long.MAX_VALUE))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReplayGuardConfiguration.Local(path, invalid));
        }
        assertThatNullPointerException()
                .isThrownBy(() -> new ReplayGuardConfiguration.Local(null, Duration.ofMillis(1)));
        assertThatNullPointerException()
                .isThrownBy(() -> new ReplayGuardConfiguration.Local(path, null));
    }

    @Test
    void backendSelectionRequiresExactlyItsOwnSettings() {
        var local =
                new ReplayGuardConfiguration.Local(
                        directory.resolve("replay.db"), Duration.ofMillis(1));
        var redis = redis("localhost", 6379, 0, "chorus");
        assertThat(
                        new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.LOCAL, local, null)
                                .backend())
                .isEqualTo(ReplayGuardConfiguration.Backend.LOCAL);
        assertThat(
                        new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.REDIS, null, redis)
                                .backend())
                .isEqualTo(ReplayGuardConfiguration.Backend.REDIS);
        assertThat(
                        new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.REGISTRY, null, null)
                                .backend())
                .isEqualTo(ReplayGuardConfiguration.Backend.REGISTRY);
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.LOCAL, local, redis));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.REDIS, local, redis));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.REGISTRY, local, null));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.REGISTRY, null, redis));
        assertThatNullPointerException()
                .isThrownBy(() -> new ReplayGuardConfiguration(null, local, null));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.LOCAL, null, null));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.REDIS, null, null));
    }

    @Test
    void redisEndpointValidationAcceptsOnlyBoundedNonBlankValues() {
        assertThat(redis("h", 1, 0, "u").port()).isEqualTo(1);
        assertThat(redis("h", 65535, Integer.MAX_VALUE, "u").database())
                .isEqualTo(Integer.MAX_VALUE);
        for (int port : new int[] {0, -1, 65536}) {
            assertThatIllegalArgumentException().isThrownBy(() -> redis("h", port, 0, "u"));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> redis("h", 6379, -1, "u"));
        for (String host : new String[] {null, "", " "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> redis(host, 6379, 0, "u"));
        }
        for (String user : new String[] {null, "", " "}) {
            assertThatIllegalArgumentException().isThrownBy(() -> redis("h", 6379, 0, user));
        }
        var valid = redis("h", 6379, 0, "u");
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Redis(
                                        valid.host(),
                                        valid.port(),
                                        valid.database(),
                                        valid.username(),
                                        null,
                                        valid.tls(),
                                        valid.timeouts(),
                                        valid.pool()));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Redis(
                                        valid.host(),
                                        valid.port(),
                                        valid.database(),
                                        valid.username(),
                                        valid.passwordPath(),
                                        null,
                                        valid.timeouts(),
                                        valid.pool()));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Redis(
                                        valid.host(),
                                        valid.port(),
                                        valid.database(),
                                        valid.username(),
                                        valid.passwordPath(),
                                        valid.tls(),
                                        null,
                                        valid.pool()));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Redis(
                                        valid.host(),
                                        valid.port(),
                                        valid.database(),
                                        valid.username(),
                                        valid.passwordPath(),
                                        valid.tls(),
                                        valid.timeouts(),
                                        null));
    }

    @Test
    void tlsRequiresAModeAndForbidsDisabledCertificates() {
        assertThat(
                        new ReplayGuardConfiguration.Tls(
                                        ReplayGuardConfiguration.TlsMode.DISABLED, null)
                                .mode())
                .isEqualTo(ReplayGuardConfiguration.TlsMode.DISABLED);
        assertThat(
                        new ReplayGuardConfiguration.Tls(
                                        ReplayGuardConfiguration.TlsMode.VERIFY_FULL, null)
                                .mode())
                .isEqualTo(ReplayGuardConfiguration.TlsMode.VERIFY_FULL);
        var ca = directory.resolve("ca.pem");
        assertThat(
                        new ReplayGuardConfiguration.Tls(
                                        ReplayGuardConfiguration.TlsMode.VERIFY_FULL, ca)
                                .caCert())
                .isEqualTo(ca);
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Tls(
                                        ReplayGuardConfiguration.TlsMode.DISABLED, ca));
        assertThatNullPointerException()
                .isThrownBy(() -> new ReplayGuardConfiguration.Tls(null, null));
    }

    @Test
    void timeoutsRequireBoundedWholeMillisecondsAndMinimumAcquireWait() {
        assertThat(
                        new ReplayGuardConfiguration.Timeouts(
                                        Duration.ofMillis(1),
                                        Duration.ofMillis(1),
                                        Duration.ofMillis(500))
                                .acquire())
                .isEqualTo(Duration.ofMillis(500));
        assertThat(
                        new ReplayGuardConfiguration.Timeouts(
                                        Duration.ofMillis(Integer.MAX_VALUE),
                                        Duration.ofMillis(Integer.MAX_VALUE),
                                        Duration.ofMillis(Integer.MAX_VALUE))
                                .connect())
                .isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
        for (Duration invalid :
                java.util.List.of(
                        Duration.ZERO,
                        Duration.ofNanos(1),
                        Duration.ofMillis(-1),
                        Duration.ofMillis((long) Integer.MAX_VALUE + 1),
                        Duration.ofMillis(Long.MAX_VALUE))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new ReplayGuardConfiguration.Timeouts(
                                            invalid, Duration.ofMillis(1), Duration.ofMillis(500)));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new ReplayGuardConfiguration.Timeouts(
                                            Duration.ofMillis(1), invalid, Duration.ofMillis(500)));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new ReplayGuardConfiguration.Timeouts(
                                            Duration.ofMillis(1), Duration.ofMillis(1), invalid));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Timeouts(
                                        Duration.ofMillis(1),
                                        Duration.ofMillis(1),
                                        Duration.ofMillis(499)));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Timeouts(
                                        null, Duration.ofMillis(1), Duration.ofMillis(500)));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Timeouts(
                                        Duration.ofMillis(1), null, Duration.ofMillis(500)));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Timeouts(
                                        Duration.ofMillis(1), Duration.ofMillis(1), null));
    }

    @Test
    void poolRequiresAPositiveSize() {
        assertThat(new ReplayGuardConfiguration.Pool(1).size()).isEqualTo(1);
        assertThat(new ReplayGuardConfiguration.Pool(Integer.MAX_VALUE).size())
                .isEqualTo(Integer.MAX_VALUE);
        for (int size : new int[] {0, -1}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReplayGuardConfiguration.Pool(size));
        }
    }

    private ReplayGuardConfiguration.Redis redis(
            String host, int port, int database, String username) {
        return new ReplayGuardConfiguration.Redis(
                host,
                port,
                database,
                username,
                directory.resolve("password.txt"),
                new ReplayGuardConfiguration.Tls(ReplayGuardConfiguration.TlsMode.DISABLED, null),
                new ReplayGuardConfiguration.Timeouts(
                        Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(500)),
                new ReplayGuardConfiguration.Pool(1));
    }
}
