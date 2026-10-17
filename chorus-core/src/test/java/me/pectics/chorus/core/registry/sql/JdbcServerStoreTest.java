package me.pectics.chorus.core.registry.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import me.pectics.chorus.core.config.model.RegistryConfiguration.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcServerStoreTest {
    @ParameterizedTest
    @EnumSource(Engine.class)
    void normalizesTlsAndTimeoutUnitsWithoutPuttingCredentialsInUrls(Engine engine) {
        var settings = settings(engine, "2001:db8::1", "chorus?user=other&password=hidden");
        var url = JdbcServerStore.jdbcUrl(settings);
        assertThat(url).contains("[2001:db8::1]:5432/chorus%3Fuser%3Dother%26password%3Dhidden");
        assertThat(url).doesNotContain("secret-user");
        var properties = JdbcServerStore.driverProperties(settings);
        if (engine == Engine.POSTGRESQL) {
            assertThat(properties.getProperty("connectTimeout")).isEqualTo("2");
            assertThat(properties.getProperty("socketTimeout")).isEqualTo("3");
            assertThat(properties.getProperty("sslmode")).isEqualTo("verify-full");
            assertThat(properties.getProperty("sslfactory"))
                    .isEqualTo("org.postgresql.ssl.DefaultJavaSSLFactory");
        } else {
            assertThat(properties.getProperty("connectTimeout")).isEqualTo("1500");
            assertThat(properties.getProperty("socketTimeout")).isEqualTo("2500");
            assertThat(properties.getProperty("sslMode"))
                    .isEqualTo(engine == Engine.MYSQL ? "VERIFY_IDENTITY" : "verify-full");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"host?sslMode=DISABLED", "user@host", "host/path", "host#fragment"})
    void rejectsHostnamesContainingConnectionParameters(String host) {
        assertThatThrownBy(() -> JdbcServerStore.jdbcUrl(settings(Engine.MYSQL, host, "chorus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid SQL hostname");
    }

    private Sql settings(Engine engine, String host, String database) {
        return new Sql(
                engine,
                host,
                5432,
                database,
                "secret-user",
                Path.of("password.txt"),
                new Tls(TlsMode.VERIFY_FULL, null),
                new Pool(2, Duration.ofSeconds(3)),
                new Timeouts(
                        Duration.ofMillis(1500), Duration.ofSeconds(5), Duration.ofMillis(2500)));
    }
}
