/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/RegistryConfiguration.java
 * sync: Keep SQL limits and provider-required settings validated before connection assembly.
 * @endcontext
 */
package me.pectics.chorus.core.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Path;
import java.time.Duration;
import me.pectics.chorus.core.registry.RegistryConfiguration.Cache;
import me.pectics.chorus.core.registry.RegistryConfiguration.Config;
import me.pectics.chorus.core.registry.RegistryConfiguration.Engine;
import me.pectics.chorus.core.registry.RegistryConfiguration.Pool;
import me.pectics.chorus.core.registry.RegistryConfiguration.Provider;
import me.pectics.chorus.core.registry.RegistryConfiguration.Sql;
import me.pectics.chorus.core.registry.RegistryConfiguration.Timeouts;
import me.pectics.chorus.core.registry.RegistryConfiguration.Tls;
import me.pectics.chorus.core.registry.RegistryConfiguration.TlsMode;
import org.junit.jupiter.api.Test;

class RegistryConfigurationTest {
    private static final Path PATH = Path.of("state/servers.yml");

    @Test
    void providerRequiresOnlyItsOwnResourceSettings() {
        assertThat(new RegistryConfiguration(Provider.CONFIG, new Config(PATH)).provider())
                .isEqualTo(Provider.CONFIG);
        assertThat(
                        new RegistryConfiguration(Provider.CONFIG, new Config(PATH), sql(), cache())
                                .sql())
                .isEqualTo(sql());
        assertThatNullPointerException()
                .isThrownBy(() -> new RegistryConfiguration(Provider.CONFIG, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new RegistryConfiguration(Provider.SQL, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new RegistryConfiguration(Provider.SQL, null, sql(), null));
        assertThatNullPointerException()
                .isThrownBy(() -> new RegistryConfiguration(null, new Config(PATH)));
        assertThatNullPointerException().isThrownBy(() -> new Config(null));
        assertThatNullPointerException().isThrownBy(() -> new Tls(null, null));
    }

    @Test
    void sqlEndpointRejectsMissingBlankAndOutOfRangeValues() {
        assertThat(sql().port()).isEqualTo(5432);
        assertThat(sqlWith("localhost", 1, "database", "username").port()).isEqualTo(1);
        assertThat(sqlWith("localhost", 65535, "database", "username").port()).isEqualTo(65535);
        for (String invalid : new String[] {null, "", " "}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sqlWith(invalid, 5432, "database", "username"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sqlWith("localhost", 5432, invalid, "username"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sqlWith("localhost", 5432, "database", invalid));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sqlWith("localhost", 0, "database", "username"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> sqlWith("localhost", 65536, "database", "username"));

        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new Sql(
                                        null,
                                        "localhost",
                                        5432,
                                        "database",
                                        "username",
                                        PATH,
                                        new Tls(TlsMode.DISABLED, null),
                                        new Pool(1, Duration.ofMillis(500)),
                                        new Timeouts(
                                                Duration.ofMillis(1),
                                                Duration.ofMillis(1),
                                                Duration.ofMillis(1))));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new Sql(
                                        Engine.POSTGRESQL,
                                        "localhost",
                                        5432,
                                        "database",
                                        "username",
                                        null,
                                        new Tls(TlsMode.DISABLED, null),
                                        new Pool(1, Duration.ofMillis(500)),
                                        new Timeouts(
                                                Duration.ofMillis(1),
                                                Duration.ofMillis(1),
                                                Duration.ofMillis(1))));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new Sql(
                                        Engine.POSTGRESQL,
                                        "localhost",
                                        5432,
                                        "database",
                                        "username",
                                        PATH,
                                        null,
                                        null,
                                        null));
    }

    @Test
    void tlsDisabledRejectsAnUnusedCertificateAuthority() {
        var ca = Path.of("ca.pem");
        assertThat(new Tls(TlsMode.DISABLED, null).caCert()).isNull();
        assertThat(new Tls(TlsMode.VERIFY_FULL, ca).caCert()).isEqualTo(ca);
        assertThatIllegalArgumentException().isThrownBy(() -> new Tls(TlsMode.DISABLED, ca));
    }

    @Test
    void poolAndTimeoutValuesAreBoundedWholeMilliseconds() {
        assertThat(new Pool(1, Duration.ofMillis(500)).acquireTimeout())
                .isEqualTo(Duration.ofMillis(500));
        assertThatIllegalArgumentException().isThrownBy(() -> new Pool(0, Duration.ofMillis(500)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Pool(1, Duration.ofMillis(499)));
        assertThatIllegalArgumentException().isThrownBy(() -> new Pool(1, Duration.ofNanos(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Pool(1, Duration.ofMillis(500).plusNanos(1)));
        assertThatNullPointerException().isThrownBy(() -> new Pool(1, null));

        assertThat(
                        new Timeouts(
                                        Duration.ofMillis(1),
                                        Duration.ofMillis(1),
                                        Duration.ofMillis(Integer.MAX_VALUE))
                                .socket())
                .isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
        for (Duration invalid :
                new Duration[] {
                    Duration.ZERO,
                    Duration.ofNanos(1),
                    Duration.ofMillis(500).plusNanos(1),
                    Duration.ofMillis((long) Integer.MAX_VALUE + 1)
                }) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new Timeouts(
                                            invalid, Duration.ofMillis(1), Duration.ofMillis(1)));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new Timeouts(
                                            Duration.ofMillis(1), invalid, Duration.ofMillis(1)));
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new Timeouts(
                                            Duration.ofMillis(1), Duration.ofMillis(1), invalid));
        }
        assertThatNullPointerException()
                .isThrownBy(() -> new Timeouts(null, Duration.ofMillis(1), Duration.ofMillis(1)));
    }

    @Test
    void cacheRequiresAStrictlyLongerStaleLimit() {
        assertThat(new Cache(Duration.ofMillis(1), Duration.ofMillis(2)).maxStale())
                .isEqualTo(Duration.ofMillis(2));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Cache(Duration.ofMillis(2), Duration.ofMillis(2)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Cache(Duration.ofMillis(2), Duration.ofMillis(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Cache(Duration.ZERO, Duration.ofMillis(1)));
        assertThatNullPointerException().isThrownBy(() -> new Cache(null, Duration.ofMillis(1)));
    }

    private static Sql sql() {
        return sqlWith("localhost", 5432, "database", "username");
    }

    private static Sql sqlWith(String host, int port, String database, String username) {
        return new Sql(
                Engine.POSTGRESQL,
                host,
                port,
                database,
                username,
                PATH,
                new Tls(TlsMode.DISABLED, null),
                new Pool(2, Duration.ofSeconds(3)),
                new Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    private static Cache cache() {
        return new Cache(Duration.ofSeconds(10), Duration.ofSeconds(20));
    }
}
