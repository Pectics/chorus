/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3, 3.9, and 7.2
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/registry/sql/v1/
 * sync: Keep fixed table names, schema version, driver TLS mappings, and initialization scripts aligned.
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import me.pectics.chorus.core.config.model.RegistryConfiguration.Engine;
import me.pectics.chorus.core.config.model.RegistryConfiguration.Sql;
import me.pectics.chorus.core.config.model.RegistryConfiguration.TlsMode;
import me.pectics.chorus.core.registry.ServerNode;

/**
 * Pooled JDBC reads of the versioned server directory. Opening loads credentials and may block;
 * load and close must run off event loops. Never creates tables or loads issuer keys. Owns its pool
 * and any connection-specific truststore; no JVM-wide TLS properties are changed.
 */
public final class JdbcServerStore implements SqlServerRegistry.Store {
    private final HikariDataSource pool;
    private final Engine engine;
    private final int querySeconds;
    private final Path truststore;

    private JdbcServerStore(HikariDataSource pool, Sql settings, Path truststore) {
        this.pool = pool;
        this.engine = settings.engine();
        this.querySeconds = seconds(settings.timeouts().query());
        this.truststore = truststore;
    }

    /**
     * Reads one UTF-8 password file; removes one terminal LF/CRLF, preserving all other whitespace.
     */
    public static JdbcServerStore open(Sql settings) throws IOException {
        Path truststore = null;
        try {
            var config = new HikariConfig();
            config.setPoolName(
                    "chorus-directory-" + settings.engine().name().toLowerCase(Locale.ROOT));
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
            config.setReadOnly(true);
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
            return new JdbcServerStore(new HikariDataSource(config), settings, truststore);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            if (truststore != null) Files.deleteIfExists(truststore);
            // Driver/configuration exceptions may contain secrets; never attach their diagnostics.
            throw new IOException(
                    "Unable to initialize SQL connection settings or credential/CA files");
        }
    }

    static String jdbcUrl(Sql settings) {
        try {
            var endpoint = new URI("sql", null, settings.host(), settings.port(), null, null, null);
            if (endpoint.getPort() != settings.port()
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

    @Override
    public Map<String, ServerNode> load() throws SQLException {
        // TODO: Make PostgreSQL schema selection explicit when sql.schema is introduced (§7.2).
        String prefix = engine == Engine.POSTGRESQL ? "public." : "";
        try (var connection = pool.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String expected =
                    switch (engine) {
                        case POSTGRESQL -> "PostgreSQL";
                        case MYSQL -> "MySQL";
                        case MARIADB -> "MariaDB";
                    };
            if (!expected.equals(product)) throw new SQLException("SQL engine mismatch");
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(querySeconds);
                try (var version =
                        statement.executeQuery(
                                "SELECT version FROM "
                                        + prefix
                                        + "chorus_registry_schema WHERE singleton = 1")) {
                    if (!version.next() || version.getInt(1) != 1 || version.next()) {
                        throw new SQLException("Unsupported SQL registry schema version");
                    }
                }
                var nodes = new HashMap<String, ServerNode>();
                try (var rows =
                        statement.executeQuery(
                                "SELECT node_id, display_name, host, port FROM "
                                        + prefix
                                        + "chorus_servers")) {
                    while (rows.next()) {
                        var node =
                                new ServerNode(
                                        rows.getString(1),
                                        rows.getString(2),
                                        rows.getString(3),
                                        rows.getInt(4));
                        if (nodes.putIfAbsent(node.nodeId(), node) != null) {
                            throw new SQLException("Duplicate SQL node ID");
                        }
                    }
                }
                connection.commit();
                return Map.copyOf(nodes);
            }
        } catch (SQLException failure) {
            throw new SQLException(
                    "Unable to read SQL server directory; check connection, schema version, and rows",
                    failure.getSQLState(),
                    failure.getErrorCode());
        }
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
