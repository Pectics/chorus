/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.3, 3.9, and 6
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/registry/sql/v1/;
 *          chorus-core/build.gradle.kts
 * sync: Exercise each native driver, schema, and TLS mode against real opt-in database services.
 * @endcontext
 */
package me.pectics.chorus.core.registry.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.bootstrap.ChorusCoreBootstrap;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;
import me.pectics.chorus.core.identity.ReceiverLoginContext;
import me.pectics.chorus.core.registry.RegistryConfiguration.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("resource")
class SqlServerNodeRegistryIntegrationTest {
    private static final String RESOURCE = "/me/pectics/chorus/core/registry/sql/";
    private static final String ADMIN_PASSWORD = "sql-integration-admin";
    private static final String PASSWORD = " fixture-password ";
    @TempDir Path directory;

    @ParameterizedTest(name = "{0}: schema, refresh, TLS, and configured engine")
    @EnumSource(Engine.class)
    void validatesRealDatabaseContract(Engine engine) throws Exception {
        Path ca = directory.resolve("registry-ca.pem");
        Files.write(ca, resource("tls/ca.pem"));
        Files.writeString(directory.resolve("password.txt"), PASSWORD + "\r\n");
        try (var server = container(engine)) {
            try {
                server.start();
            } catch (RuntimeException failure) {
                throw new IllegalStateException(
                        "SQL fixture startup failed: " + server.getLogs(), failure);
            }
            assertThat(server.getHost())
                    .as("TLS fixtures require a local Docker daemon")
                    .isIn("localhost", "127.0.0.1");
            int port = server.getMappedPort(engine == Engine.POSTGRESQL ? 5432 : 3306);
            try (var admin = awaitAdmin(engine, port)) {
                createUser(admin, engine);
                try (var missing =
                        JdbcServerNodeStore.open(
                                settings(engine, port, TlsMode.VERIFY_FULL, ca, "localhost"))) {
                    assertThatThrownBy(missing::load).isInstanceOf(SQLException.class);
                }
                initialize(admin, engine);
                grantRead(admin, engine);
                execute(
                        admin,
                        "INSERT INTO chorus_servers (node_id, display_name, host, port) VALUES "
                                + "('gateway', 'Gateway', 'gateway.example.net', 25565),"
                                + "('backend', '生存服', 'backend.example.net', 25565),"
                                + "('Backend', 'Case-sensitive ID', 'other.example.net', 25566)");

                verifyTls(engine, port, ca);
                verifyRefreshAndFailures(admin, engine, port, ca);
                verifyLocalTrustAndConfiguredEngines(admin, engine, port, ca);
            }
        }
    }

    private void verifyTls(Engine engine, int port, Path ca) throws Exception {
        try (var valid =
                JdbcServerNodeStore.open(
                        settings(engine, port, TlsMode.VERIFY_FULL, ca, "localhost"))) {
            var serverNodes = valid.load();
            assertThat(serverNodes).containsKeys("backend", "Backend");
            assertThat(serverNodes.get("backend").displayName()).isEqualTo("生存服");
        }
        try (var wrongHost =
                JdbcServerNodeStore.open(
                        settings(engine, port, TlsMode.VERIFY_FULL, ca, "127.0.0.1"))) {
            assertThatThrownBy(wrongHost::load).isInstanceOf(SQLException.class);
        }
        try (var untrusted =
                JdbcServerNodeStore.open(
                        settings(engine, port, TlsMode.VERIFY_FULL, null, "localhost"))) {
            assertThatThrownBy(untrusted::load).isInstanceOf(SQLException.class);
        }
        // MySQL's caching_sha2_password fast authentication is warmed by the successful TLS login.
        try (var plain =
                JdbcServerNodeStore.open(
                        settings(engine, port, TlsMode.DISABLED, null, "localhost"))) {
            assertThat(plain.load()).hasSize(3);
        }
    }

    private void verifyRefreshAndFailures(Connection admin, Engine engine, int port, Path ca)
            throws Exception {
        var ticker = new AtomicLong();
        var cache = new Cache(Duration.ofMinutes(1), Duration.ofMinutes(2));
        try (var registry =
                new SqlServerNodeRegistry(
                        JdbcServerNodeStore.open(
                                settings(engine, port, TlsMode.VERIFY_FULL, ca, "localhost")),
                        cache,
                        Set.of("gateway", "backend"),
                        Clock.systemUTC(),
                        ticker::get)) {
            var original = registry.snapshot();
            execute(
                    admin,
                    "UPDATE chorus_servers SET host='updated.example.net', port=25567 WHERE node_id='backend'");
            execute(admin, "DELETE FROM chorus_servers WHERE node_id='Backend'");
            ticker.set(Duration.ofSeconds(90).toNanos());
            refresh(registry);
            assertThat(registry.find("backend").orElseThrow().port()).isEqualTo(25567);
            assertThat(registry.find("Backend")).isEmpty();
            assertThat(original.serverNodes().get("backend").port()).isEqualTo(25565);

            execute(admin, "UPDATE chorus_servers SET host=' ' WHERE node_id='backend'");
            assertRefreshFailure(registry);
            assertThat(registry.find("backend").orElseThrow().host())
                    .isEqualTo("updated.example.net");
            execute(
                    admin,
                    "UPDATE chorus_servers SET host='updated.example.net' WHERE node_id='backend'");

            execute(admin, "DROP TABLE chorus_registry_schema");
            execute(
                    admin,
                    "CREATE TABLE chorus_registry_schema (singleton INTEGER PRIMARY KEY, version INTEGER NOT NULL)");
            execute(admin, "INSERT INTO chorus_registry_schema VALUES (1, 2)");
            grantRead(admin, engine);
            assertRefreshFailure(registry);
            ticker.set(Duration.ofSeconds(210).toNanos());
            assertThatThrownBy(registry::snapshot).isInstanceOf(IllegalStateException.class);
            execute(admin, "UPDATE chorus_registry_schema SET version=1");
            refresh(registry);
            assertThat(registry.snapshot().stale()).isFalse();

            try {
                if (engine == Engine.POSTGRESQL) {
                    admin.setAutoCommit(false);
                    execute(admin, "LOCK TABLE chorus_servers IN ACCESS EXCLUSIVE MODE");
                } else execute(admin, "LOCK TABLES chorus_servers WRITE");
                assertRefreshFailure(registry);
            } finally {
                if (engine == Engine.POSTGRESQL) {
                    admin.rollback();
                    admin.setAutoCommit(true);
                } else execute(admin, "UNLOCK TABLES");
            }
            refresh(registry);
        }
    }

    private void verifyLocalTrustAndConfiguredEngines(
            Connection admin, Engine engine, int port, Path ca) throws Exception {
        var pair = TestSupport.keyPair();
        Files.write(directory.resolve("private.der"), pair.getPrivate().getEncoded());
        Files.write(directory.resolve("public.der"), pair.getPublic().getEncoded());
        for (String algorithm : new String[] {"ED25519", "HMAC_SHA256"}) {
            String key = algorithm.equals("ED25519") ? "public.der" : "shared.key";
            Files.writeString(
                    directory.resolve("trusted-issuers.yml"),
                    """
                    gateway:
                      algorithm: %s
                      key: %s
                      allowed-auth-schemes: [MOJANG]
                      allowed-providers: [mojang]
                    """
                            .formatted(algorithm, key));
            Path senderFile = write("gateway.yml", configuration(engine, port, algorithm, true));
            Path receiverFile = write("backend.yml", configuration(engine, port, algorithm, false));
            try (var sender = ChorusCoreBootstrap.open(senderFile);
                    var receiver = ChorusCoreBootstrap.open(receiverFile)) {
                var ticket =
                        sender.issueTicket(
                                        new IssueTicketRequest(
                                                "backend",
                                                new AuthenticatedIdentity(
                                                        TestSupport.identity(), "gateway"),
                                                Map.of()))
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS);
                assertThat(ticket.targetServer().port()).isEqualTo(25567);
                Files.writeString(
                        directory.resolve("trusted-issuers.yml"),
                        "invalid: [local-trust-is-not-reloaded]");
                refresh((SqlServerNodeRegistry) sender.getServerNodeRegistry());
                refresh((SqlServerNodeRegistry) receiver.getServerNodeRegistry());

                if (algorithm.equals("HMAC_SHA256")) {
                    execute(admin, "DROP TABLE chorus_servers");
                    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                    while (true) {
                        try {
                            sender.getServerNodeRegistry().snapshot();
                        } catch (IllegalStateException expired) {
                            break;
                        }
                        if (System.nanoTime() >= deadline)
                            throw new AssertionError("SQL cache did not expire");
                        Thread.sleep(20);
                    }
                    assertThatThrownBy(
                                    () ->
                                            sender.issueTicket(
                                                            new IssueTicketRequest(
                                                                    "backend",
                                                                    new AuthenticatedIdentity(
                                                                            TestSupport.identity(),
                                                                            "gateway"),
                                                                    Map.of()))
                                                    .toCompletableFuture()
                                                    .get(5, TimeUnit.SECONDS))
                            .hasRootCauseMessage("REGISTRY_UNAVAILABLE");
                }
                var request =
                        new VerifyTicketRequest(
                                ticket.cookiePayload(),
                                new ReceiverLoginContext("Notch", true, null));
                var result =
                        receiver.verifyAndResolve(request)
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS);
                assertThat(result.resolvedProfile().finalUuid()).isEqualTo(TestSupport.UUID_MOJANG);
                assertThatThrownBy(
                                () ->
                                        receiver.verifyAndResolve(request)
                                                .toCompletableFuture()
                                                .get(5, TimeUnit.SECONDS))
                        .hasRootCauseMessage("REPLAYED");
            }
        }
        assertThat(directory.resolve("servers.yml")).doesNotExist();
        try (var tables =
                admin.getMetaData().getTables(null, null, "chorus_%", new String[] {"TABLE"})) {
            while (tables.next()) {
                assertThat(tables.getString("TABLE_NAME"))
                        .doesNotContain("trust", "key", "ticket", "presence");
            }
        }
    }

    private String configuration(Engine engine, int port, String algorithm, boolean sender) {
        String crypto =
                algorithm.equals("ED25519")
                        ? "algorithm: ED25519\n  private-key: private.der\n  public-key: public.der"
                        : "algorithm: HMAC_SHA256\n  key: shared.key";
        return """
                node: {id: %s, role: %s}
                ticket: {cookie-key: 'chorus:ticket', max-payload-bytes: 4096, max-property-bytes: 2048, compression: NONE}
                crypto:
                  %s
                registry:
                  provider: SQL
                  sql:
                    engine: %s
                    host: localhost
                    port: %d
                    database: chorus
                    username: chorus_user
                    password-path: password.txt
                    tls: {mode: VERIFY_FULL, ca-cert: registry-ca.pem}
                    pool: {max-connections: 2, acquire-timeout: 3s}
                    timeouts: {connect: 1s, query: 1s, socket: 2s}
                  cache: {refresh-interval: 100ms, max-stale: 1500ms}
                issuance:
                  ttl: 15s
                  allowed-auth-schemes: [MOJANG]
                  allowed-providers: [mojang]
                  allowed-targets: [backend]
                verification: {trusted-issuers-path: trusted-issuers.yml, max-lifetime: 15s, clock-skew: 0s, enforce-transfer-intent: true, identity-policy: ADOPT_UPSTREAM}
                replay-guard:
                  backend: LOCAL
                  local: {path: state/%s-%s.db, max-entries: 100, busy-timeout: 500ms}
                """
                .formatted(
                        sender ? "gateway" : "backend",
                        sender ? "GATEWAY" : "BACKEND",
                        crypto,
                        engine,
                        port,
                        sender ? "gateway" : "backend",
                        algorithm);
    }

    private Sql settings(Engine engine, int port, TlsMode mode, Path ca, String host) {
        return new Sql(
                engine,
                host,
                port,
                "chorus",
                "chorus_user",
                directory.resolve("password.txt"),
                new Tls(mode, ca),
                new Pool(2, Duration.ofSeconds(2)),
                new Timeouts(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private GenericContainer<?> container(Engine engine) throws IOException {
        String name = engine.name().toLowerCase(Locale.ROOT);
        var container =
                new GenericContainer<>(
                                DockerImageName.parse(
                                        System.getProperty("chorus.sql." + name + ".image")))
                        .withExposedPorts(engine == Engine.POSTGRESQL ? 5432 : 3306)
                        .withStartupTimeout(Duration.ofMinutes(3))
                        .withCopyToContainer(
                                Transferable.of(resource("tls/ca.pem"), 0644),
                                "/tmp/chorus-tls/ca.pem")
                        .withCopyToContainer(
                                Transferable.of(resource("tls/server.pem"), 0644),
                                "/tmp/chorus-tls/server.pem")
                        .withCopyToContainer(
                                Transferable.of(resource("tls/server.key"), 0644),
                                "/tmp/chorus-tls/server.key");
        if (engine == Engine.POSTGRESQL) {
            container
                    .withEnv("POSTGRES_DB", "chorus")
                    .withEnv("POSTGRES_PASSWORD", ADMIN_PASSWORD)
                    .withCommand(
                            "sh",
                            "-c",
                            "chown postgres:postgres /tmp/chorus-tls/server.key && chmod 600 /tmp/chorus-tls/server.key "
                                    + "&& exec docker-entrypoint.sh postgres -c ssl=on -c ssl_ca_file=/tmp/chorus-tls/ca.pem "
                                    + "-c ssl_cert_file=/tmp/chorus-tls/server.pem -c ssl_key_file=/tmp/chorus-tls/server.key");
        } else {
            String env = engine == Engine.MYSQL ? "MYSQL" : "MARIADB";
            container
                    .withEnv(env + "_DATABASE", "chorus")
                    .withEnv(env + "_ROOT_PASSWORD", ADMIN_PASSWORD)
                    .withEnv(env + "_ROOT_HOST", "%")
                    .withCommand(
                            "--ssl-ca=/tmp/chorus-tls/ca.pem",
                            "--ssl-cert=/tmp/chorus-tls/server.pem",
                            "--ssl-key=/tmp/chorus-tls/server.key");
        }
        return container;
    }

    private Connection awaitAdmin(Engine engine, int port) throws Exception {
        var properties = new Properties();
        properties.setProperty("user", engine == Engine.POSTGRESQL ? "postgres" : "root");
        properties.setProperty("password", ADMIN_PASSWORD);
        properties.setProperty(
                engine == Engine.POSTGRESQL ? "sslmode" : "sslMode",
                engine == Engine.POSTGRESQL
                        ? "require"
                        : engine == Engine.MYSQL ? "REQUIRED" : "trust");
        String url =
                "jdbc:"
                        + engine.name().toLowerCase(Locale.ROOT)
                        + "://localhost:"
                        + port
                        + "/chorus";
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                return DriverManager.getConnection(url, properties);
            } catch (SQLException failure) {
                if (System.nanoTime() >= deadline) throw failure;
                Thread.sleep(100);
            }
        }
    }

    private void createUser(Connection admin, Engine engine) throws SQLException {
        execute(
                admin,
                engine == Engine.POSTGRESQL
                        ? "CREATE USER chorus_user WITH PASSWORD '" + PASSWORD + "'"
                        : "CREATE USER 'chorus_user'@'%' IDENTIFIED BY '" + PASSWORD + "'");
    }

    private void grantRead(Connection admin, Engine engine) throws SQLException {
        execute(
                admin,
                engine == Engine.POSTGRESQL
                        ? "GRANT SELECT ON ALL TABLES IN SCHEMA public TO chorus_user"
                        : "GRANT SELECT ON chorus.* TO 'chorus_user'@'%'");
    }

    private void initialize(Connection admin, Engine engine) throws Exception {
        String script =
                new String(
                        resource("v1/" + engine.name().toLowerCase(Locale.ROOT) + ".sql"),
                        StandardCharsets.UTF_8);
        for (String statement : script.split(";")) {
            if (!statement.isBlank()) execute(admin, statement);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void refresh(SqlServerNodeRegistry registry) throws Exception {
        registry.refresh().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void assertRefreshFailure(SqlServerNodeRegistry registry) {
        assertThatThrownBy(() -> refresh(registry))
                .hasRootCauseMessage("SQL server node registry refresh failed");
    }

    private byte[] resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(RESOURCE + name)) {
            if (input == null) throw new IOException("Missing SQL integration resource " + name);
            return input.readAllBytes();
        }
    }

    private Path write(String file, String value) throws IOException {
        return Files.writeString(directory.resolve(file), value);
    }
}
