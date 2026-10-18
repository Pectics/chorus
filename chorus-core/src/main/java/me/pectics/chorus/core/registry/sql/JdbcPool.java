/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3, 3.9, and 7.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/RegistryConfiguration.java
 * sync: Keep endpoint, TLS, credential, and pool mappings in one place for read and write consumers.
 * @endcontext
 */
package me.pectics.chorus.core.registry.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import me.pectics.chorus.core.registry.RegistryConfiguration.Engine;
import me.pectics.chorus.core.registry.RegistryConfiguration.Sql;
import me.pectics.chorus.core.registry.RegistryConfiguration.TlsMode;

/**
 * Pooled JDBC endpoint with read or write intent. Opening loads credentials and may block; callers
 * use it off event loops. Owns its pool and any connection-specific truststore; no JVM-wide TLS
 * properties are changed.
 */
public final class JdbcPool implements AutoCloseable {
    private final HikariDataSource pool;
    private final Path truststore;

    private JdbcPool(HikariDataSource pool, Path truststore) {
        this.pool = pool;
        this.truststore = truststore;
    }

    /**
     * Reads one UTF-8 password file; removes one terminal LF/CRLF, preserving all other whitespace.
     */
    public static JdbcPool open(Sql settings, boolean readOnly) throws IOException {
        Path truststore = null;
        try {
            var config = new HikariConfig();
            config.setPoolName(
                    "chorus-"
                            + (readOnly ? "read" : "write")
                            + "-"
                            + settings.engine().name().toLowerCase(Locale.ROOT));
            config.setDriverClassName(
                    switch (settings.engine()) {
                        case POSTGRESQL -> "org.postgresql.Driver";
                        case MYSQL -> "com.mysql.cj.jdbc.Driver";
                        case MARIADB -> "org.mariadb.jdbc.Driver";
                    });
            config.setJdbcUrl(jdbcUrl(settings));
            config.setUsername(settings.username());
            config.setPassword(readPassword(settings.passwordPath()));
            config.setMaximumPoolSize(settings.pool().maxConnections());
            config.setMinimumIdle(0);
            long acquire = settings.pool().acquireTimeout().toMillis();
            config.setConnectionTimeout(acquire);
            config.setValidationTimeout(Math.min(1000, acquire / 2));
            config.setInitializationFailTimeout(-1);
            config.setReadOnly(readOnly);
            var properties = driverProperties(settings);
            boolean mysqlCa = settings.engine() == Engine.MYSQL && settings.tls().caCert() != null;
            boolean mariaDefaultCa =
                    settings.engine() == Engine.MARIADB
                            && settings.tls().mode() == TlsMode.VERIFY_FULL
                            && settings.tls().caCert() == null;
            if (mysqlCa || mariaDefaultCa) {
                truststore = Files.createTempFile("chorus-registry-ca-", ".p12");
                String password = UUID.randomUUID().toString();
                writeTruststore(settings.tls().caCert(), truststore, password);
                if (mysqlCa) {
                    properties.setProperty(
                            "trustCertificateKeyStoreUrl", truststore.toUri().toString());
                    properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
                    properties.setProperty("trustCertificateKeyStorePassword", password);
                } else {
                    // An explicit store prevents MariaDB's password-based zero-configuration
                    // fallback.
                    properties.setProperty("trustStore", truststore.toString());
                    properties.setProperty("trustStoreType", "PKCS12");
                    properties.setProperty("trustStorePassword", password);
                }
                properties.setProperty("fallbackToSystemTrustStore", "false");
            }
            config.setDataSourceProperties(properties);
            return new JdbcPool(new HikariDataSource(config), truststore);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (truststore != null) Files.deleteIfExists(truststore);
            // Driver/configuration exceptions may contain secrets; never attach their diagnostics.
            throw new IOException(
                    "Unable to initialize SQL connection settings or credential/CA files");
        }
    }

    public Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    static String jdbcUrl(Sql settings) {
        try {
            var endpoint = new URI("sql", null, settings.host(), settings.port(), null, null, null);
            if (endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null
                    || endpoint.getPort() != settings.port()
                    || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || endpoint.getRawPath() != null && !endpoint.getRawPath().isEmpty()) {
                throw new IllegalArgumentException("Invalid SQL hostname");
            }
            String authority = endpoint.getRawAuthority();
            String database =
                    URLEncoder.encode(settings.database(), StandardCharsets.UTF_8)
                            .replace("+", "%20");
            return "jdbc:"
                    + settings.engine().name().toLowerCase(Locale.ROOT)
                    + "://"
                    + authority
                    + "/"
                    + database;
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid SQL hostname");
        }
    }

    static Properties driverProperties(Sql settings) {
        var result = new Properties();
        boolean tls = settings.tls().mode() == TlsMode.VERIFY_FULL;
        switch (settings.engine()) {
            case POSTGRESQL -> {
                result.setProperty("connectTimeout", "" + seconds(settings.timeouts().connect()));
                result.setProperty("socketTimeout", "" + seconds(settings.timeouts().socket()));
                result.setProperty(
                        "cancelSignalTimeout", "" + seconds(settings.timeouts().connect()));
                result.setProperty("sslmode", tls ? "verify-full" : "disable");
                if (tls) {
                    if (settings.tls().caCert() == null) {
                        result.setProperty(
                                "sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
                    } else {
                        result.setProperty("sslrootcert", settings.tls().caCert().toString());
                    }
                }
            }
            case MYSQL -> {
                result.setProperty("connectTimeout", "" + settings.timeouts().connect().toMillis());
                result.setProperty("socketTimeout", "" + settings.timeouts().socket().toMillis());
                result.setProperty("sslMode", tls ? "VERIFY_IDENTITY" : "DISABLED");
                result.setProperty("paranoid", "true");
                result.setProperty("allowLoadLocalInfile", "false");
            }
            case MARIADB -> {
                result.setProperty("connectTimeout", "" + settings.timeouts().connect().toMillis());
                result.setProperty("socketTimeout", "" + settings.timeouts().socket().toMillis());
                result.setProperty("sslMode", tls ? "verify-full" : "disable");
                result.setProperty("allowLocalInfile", "false");
                result.setProperty("permitRedirect", "false");
                if (settings.tls().caCert() != null) {
                    result.setProperty("serverSslCert", settings.tls().caCert().toString());
                    result.setProperty("fallbackToSystemTrustStore", "false");
                }
            }
        }
        return result;
    }

    private static String readPassword(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(65537);
        }
        if (bytes.length > 65536) throw new IOException("SQL password file exceeds size limit");
        String value =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
        if (value.endsWith("\r\n")) return value.substring(0, value.length() - 2);
        if (value.endsWith("\n")) return value.substring(0, value.length() - 1);
        return value;
    }

    private static void writeTruststore(Path ca, Path output, String password)
            throws IOException, GeneralSecurityException {
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        int index = 0;
        if (ca == null) {
            var managers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init((KeyStore) null);
            for (var manager : managers.getTrustManagers()) {
                if (manager instanceof X509TrustManager trusted) {
                    for (var cert : trusted.getAcceptedIssuers()) {
                        store.setCertificateEntry("ca-" + index++, cert);
                    }
                }
            }
        } else {
            try (var input = Files.newInputStream(ca)) {
                var certificates =
                        CertificateFactory.getInstance("X.509").generateCertificates(input);
                for (var cert : certificates) store.setCertificateEntry("ca-" + index++, cert);
            }
        }
        if (index == 0) throw new GeneralSecurityException("Empty CA bundle");
        try (var file = Files.newOutputStream(output)) {
            store.store(file, password.toCharArray());
        }
    }

    private static int seconds(Duration duration) {
        return Math.toIntExact((duration.toMillis() + 999) / 1000);
    }

    @Override
    public void close() {
        try {
            pool.close();
        } finally {
            if (truststore != null) {
                try {
                    Files.deleteIfExists(truststore);
                } catch (IOException failure) {
                    throw new IllegalStateException("Unable to remove SQL truststore");
                }
            }
        }
    }
}
