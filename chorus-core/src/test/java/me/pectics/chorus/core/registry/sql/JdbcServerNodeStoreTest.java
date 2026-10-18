package me.pectics.chorus.core.registry.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import me.pectics.chorus.core.registry.RegistryConfiguration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcServerNodeStoreTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(Engine.class)
    void normalizesTlsAndTimeoutUnitsWithoutPuttingCredentialsInUrls(Engine engine) {
        var settings = settings(engine, "2001:db8::1", "chorus?user=other&password=hidden");
        var url = JdbcServerNodeStore.jdbcUrl(settings);
        assertThat(url).contains("[2001:db8::1]:5432/chorus%3Fuser%3Dother%26password%3Dhidden");
        assertThat(url).doesNotContain("secret-user");
        var properties = JdbcServerNodeStore.driverProperties(settings);
        if (engine == Engine.POSTGRESQL) {
            assertThat(properties.getProperty("connectTimeout")).isEqualTo("2");
            assertThat(properties.getProperty("socketTimeout")).isEqualTo("3");
            assertThat(properties.getProperty("cancelSignalTimeout")).isEqualTo("2");
            assertThat(properties.getProperty("sslmode")).isEqualTo("verify-full");
            assertThat(properties.getProperty("sslfactory"))
                    .isEqualTo("org.postgresql.ssl.DefaultJavaSSLFactory");
        } else {
            assertThat(properties.getProperty("connectTimeout")).isEqualTo("1500");
            assertThat(properties.getProperty("socketTimeout")).isEqualTo("2500");
            assertThat(properties.getProperty("sslMode"))
                    .isEqualTo(engine == Engine.MYSQL ? "VERIFY_IDENTITY" : "verify-full");
            if (engine == Engine.MYSQL) {
                assertThat(properties.getProperty("paranoid")).isEqualTo("true");
                assertThat(properties.getProperty("allowLoadLocalInfile")).isEqualTo("false");
            } else {
                assertThat(properties.getProperty("permitRedirect")).isEqualTo("false");
                assertThat(properties.getProperty("allowLocalInfile")).isEqualTo("false");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void disabledTlsNeverRetainsVerificationOptions(Engine engine) {
        var properties =
                JdbcServerNodeStore.driverProperties(
                        settings(engine, "localhost", "chorus", TlsMode.DISABLED, null));
        switch (engine) {
            case POSTGRESQL -> {
                assertThat(properties.getProperty("sslmode")).isEqualTo("disable");
                assertThat(properties).doesNotContainKeys("sslfactory", "sslrootcert");
            }
            case MYSQL -> assertThat(properties.getProperty("sslMode")).isEqualTo("DISABLED");
            case MARIADB -> assertThat(properties.getProperty("sslMode")).isEqualTo("disable");
        }
        assertThat(properties).doesNotContainKey("trustStore");
    }

    @Test
    void verifyFullRetainsTheConfiguredCaForEveryEngine() {
        Path ca = directory.resolve("ca.pem");
        var postgresql =
                JdbcServerNodeStore.driverProperties(
                        settings(
                                Engine.POSTGRESQL, "localhost", "chorus", TlsMode.VERIFY_FULL, ca));
        assertThat(postgresql.getProperty("sslrootcert")).isEqualTo(ca.toString());
        assertThat(postgresql).doesNotContainKey("sslfactory");
        var mysql =
                JdbcServerNodeStore.driverProperties(
                        settings(Engine.MYSQL, "localhost", "chorus", TlsMode.VERIFY_FULL, ca));
        assertThat(mysql.getProperty("sslMode")).isEqualTo("VERIFY_IDENTITY");
        var mariadb =
                JdbcServerNodeStore.driverProperties(
                        settings(Engine.MARIADB, "localhost", "chorus", TlsMode.VERIFY_FULL, ca));
        assertThat(mariadb.getProperty("serverSslCert")).isEqualTo(ca.toString());
        assertThat(mariadb.getProperty("fallbackToSystemTrustStore")).isEqualTo("false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"host?sslMode=DISABLED", "user@host", "host/path", "host#fragment"})
    void rejectsHostnamesContainingConnectionParameters(String host) {
        assertThatThrownBy(
                        () -> JdbcServerNodeStore.jdbcUrl(settings(Engine.MYSQL, host, "chorus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid SQL hostname");
    }

    @Test
    void opensWithAValidPasswordFileWithoutConnectingEagerly() throws Exception {
        Files.writeString(directory.resolve("password.txt"), " fixture-password \r\n");
        try (var store =
                JdbcServerNodeStore.open(
                        settings(
                                Engine.POSTGRESQL,
                                "localhost",
                                "chorus",
                                TlsMode.DISABLED,
                                null))) {
            assertThat(store).isNotNull();
        }
    }

    @Test
    void reportsUsableFileFailuresWithoutEchoingCredentialContents() throws Exception {
        var missingPassword =
                settings(Engine.POSTGRESQL, "localhost", "chorus", TlsMode.DISABLED, null);
        assertThatThrownBy(() -> JdbcServerNodeStore.open(missingPassword))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("password.txt");

        Files.write(directory.resolve("password.txt"), new byte[65537]);
        assertThatThrownBy(
                        () ->
                                JdbcServerNodeStore.open(
                                        settings(
                                                Engine.POSTGRESQL,
                                                "localhost",
                                                "chorus",
                                                TlsMode.DISABLED,
                                                null)))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("password.txt");

        Files.delete(directory.resolve("password.txt"));
        Files.createDirectory(directory.resolve("password.txt"));
        assertThatThrownBy(
                        () ->
                                JdbcServerNodeStore.open(
                                        settings(
                                                Engine.POSTGRESQL,
                                                "localhost",
                                                "chorus",
                                                TlsMode.DISABLED,
                                                null)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsCaBundlesThatCannotBuildAConnectionTrustStore() throws Exception {
        Files.writeString(directory.resolve("password.txt"), "fixture-password\n");
        Path ca = directory.resolve("invalid-ca.pem");
        Files.writeString(ca, "not a certificate");
        assertThatThrownBy(
                        () ->
                                JdbcServerNodeStore.open(
                                        settings(
                                                Engine.MYSQL,
                                                "localhost",
                                                "chorus",
                                                TlsMode.VERIFY_FULL,
                                                ca)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("credential/CA");
        Path empty = directory.resolve("empty-ca.pem");
        Files.writeString(empty, "");
        assertThatThrownBy(
                        () ->
                                JdbcServerNodeStore.open(
                                        settings(
                                                Engine.MYSQL,
                                                "localhost",
                                                "chorus",
                                                TlsMode.VERIFY_FULL,
                                                empty)))
                .isInstanceOf(IOException.class);
    }

    private Sql settings(Engine engine, String host, String database) {
        return settings(engine, host, database, TlsMode.VERIFY_FULL, null);
    }

    private Sql settings(Engine engine, String host, String database, TlsMode mode, Path ca) {
        return new Sql(
                engine,
                host,
                5432,
                database,
                "secret-user",
                directory.resolve("password.txt"),
                new Tls(mode, ca),
                new Pool(2, Duration.ofSeconds(3)),
                new Timeouts(
                        Duration.ofMillis(1500), Duration.ofSeconds(5), Duration.ofMillis(2500)));
    }
}
