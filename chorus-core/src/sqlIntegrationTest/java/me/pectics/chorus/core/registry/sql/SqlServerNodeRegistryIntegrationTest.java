/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.3, 3.9, and 6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/sql/JdbcSchemaInitializer.java;
 *          chorus-core/build.gradle.kts
 * sync: Exercise each native driver, schema, and TLS mode against real opt-in database services.
 * @endcontext
 */
package me.pectics.chorus.core.registry.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.bootstrap.ChorusCoreBootstrap;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;
import me.pectics.chorus.core.identity.ReceiverLoginContext;
import me.pectics.chorus.core.registry.RegistryConfiguration.*;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.registry.RegistryReplayGuard;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

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
                grantAll(admin, engine);
                try (var missing =
                        JdbcServerNodeStore.open(
                                settings(engine, port, TlsMode.VERIFY_FULL, ca, "localhost"))) {
                    assertThatThrownBy(missing::load).isInstanceOf(SQLException.class);
                }
                JdbcSchemaInitializer.initialize(
                        settings(engine, port, TlsMode.VERIFY_FULL, ca, "localhost"));
                execute(
                        admin,
                        "INSERT INTO chorus_servers (node_id, display_name, host, port) VALUES "
                                + "('gateway', 'Gateway', 'gateway.example.net', 25565),"
                                + "('backend', '生存服', 'backend.example.net', 25565),"
                                + "('Backend', 'Case-sensitive ID', 'other.example.net', 25566)");

                verifyTls(engine, port, ca);
                verifyRegistryReplay(engine, port, ca);
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
        // The registry takes ownership and closes the store, including failed initialization.
        @SuppressWarnings("resource")
        var store =
                JdbcServerNodeStore.open(
                        settings(engine, port, TlsMode.VERIFY_FULL, ca, "localhost"));
        try (var registry =
                new SqlServerNodeRegistry(
                        store,
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

            execute(admin, "DROP TABLE chorus_schema");
            execute(
                    admin,
                    "CREATE TABLE chorus_schema (singleton INTEGER PRIMARY KEY, version INTEGER NOT NULL)");
            execute(admin, "INSERT INTO chorus_schema VALUES (1, 2)");
            grantAll(admin, engine);
            assertRefreshFailure(registry);
            ticker.set(Duration.ofSeconds(210).toNanos());
            assertThatThrownBy(registry::snapshot).isInstanceOf(IllegalStateException.class);
            execute(admin, "UPDATE chorus_schema SET version=1");
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

    private void verifyRegistryReplay(Engine engine, int port, Path ca) throws Exception {
        var clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
        var settings = settings(engine, port, TlsMode.VERIFY_FULL, ca, "localhost");
        try (var first = new RegistryReplayGuard(settings, "backend", clock);
                var second = new RegistryReplayGuard(settings, "backend", clock)) {
            Instant expiresAt = clock.instant().plusSeconds(15);
            first.register("gateway", "replay-once", "backend", expiresAt)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            var attempts =
                    List.of(
                            first.consume("gateway", "replay-once", "backend", expiresAt)
                                    .toCompletableFuture(),
                            second.consume("gateway", "replay-once", "backend", expiresAt)
                                    .toCompletableFuture());
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            assertThat(attempts.stream().map(CompletableFuture::join).toList())
                    .containsExactlyInAnyOrder(ReplayDecision.ACCEPTED, ReplayDecision.REPLAYED);
            assertThat(
                            first.consume("gateway", "missing", "backend", expiresAt)
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.REPLAYED);
            first.register("gateway", "duplicate", "backend", expiresAt)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThatThrownBy(
                            () ->
                                    first.register("gateway", "duplicate", "backend", expiresAt)
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SQLException.class);
            first.register("gateway", "expired", "backend", clock.instant().plusSeconds(15))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            clock.now = clock.instant().plusSeconds(46);
            assertThat(
                            first.consume(
                                            "gateway",
                                            "expired",
                                            "backend",
                                            clock.instant().plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.REPLAYED);
            first.register("gateway", "expired", "backend", clock.instant().plusSeconds(15))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertThat(
                            first.consume(
                                            "gateway",
                                            "expired",
                                            "backend",
                                            clock.instant().plusSeconds(15))
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS))
                    .isEqualTo(ReplayDecision.ACCEPTED);
        }

        var pair = TestSupport.keyPair();
        Files.write(directory.resolve("private.der"), pair.getPrivate().getEncoded());
        Files.write(directory.resolve("public.der"), pair.getPublic().getEncoded());
        Files.writeString(
                directory.resolve("trusted-issuers.yml"),
                """
                gateway:
                  algorithm: ED25519
                  key: public.der
                  allowed-auth-schemes: [MOJANG]
                  allowed-providers: [mojang]
                """);
        Path senderFile =
                write(
                        "registry-gateway.yml",
                        configuration(engine, port, "ED25519", true, "REGISTRY"));
        Path receiverFile =
                write(
                        "registry-backend.yml",
                        configuration(engine, port, "ED25519", false, "REGISTRY"));
        try (var sender = ChorusCoreBootstrap.open(senderFile, clock);
                var receiver = ChorusCoreBootstrap.open(receiverFile, clock)) {
            var ticket =
                    sender.issueTicket(
                                    new IssueTicketRequest(
                                            "backend",
                                            new AuthenticatedIdentity(
                                                    TestSupport.identity(), "gateway"),
                                            Map.of()))
                            .toCompletableFuture()
                            .get(10, TimeUnit.SECONDS);
            var request =
                    new VerifyTicketRequest(
                            ticket.cookiePayload(), new ReceiverLoginContext("Notch", true, null));
            assertThat(
                            receiver.verifyAndResolve(request)
                                    .toCompletableFuture()
                                    .get(10, TimeUnit.SECONDS)
                                    .resolvedProfile()
                                    .finalUuid())
                    .isEqualTo(TestSupport.UUID_MOJANG);
            assertThatThrownBy(
                            () ->
                                    receiver.verifyAndResolve(request)
                                            .toCompletableFuture()
                                            .get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("REPLAYED");
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
        return configuration(engine, port, algorithm, sender, "LOCAL");
    }

    private String configuration(
            Engine engine, int port, String algorithm, boolean sender, String replayBackend) {
        String crypto =
                algorithm.equals("ED25519")
                        ? "algorithm: ED25519\n  private-key: private.der\n  public-key: public.der"
                        : "algorithm: HMAC_SHA256\n  key: shared.key";
        String base =
                """
                node: {id: %s, role: %s}
                ticket: {cookie-key: 'chorus:ticket', max-payload-bytes: 4096, max-property-bytes: 2048, max-lifetime: 15s, compression: NONE}
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
                verification: {trusted-issuers-path: trusted-issuers.yml, enforce-transfer-intent: true, identity-policy: ADOPT_UPSTREAM}
                """
                        .formatted(
                                sender ? "gateway" : "backend",
                                sender ? "GATEWAY" : "BACKEND",
                                crypto,
                                engine,
                                port);
        String replay =
                replayBackend.equals("REGISTRY")
                        ? "replay-guard: {backend: REGISTRY}\n"
                        : sender
                                ? ""
                                : ("replay-guard:\n"
                                                + "  backend: LOCAL\n"
                                                + "  local: {path: state/backend-%s.db, busy-timeout: 500ms}\n")
                                        .formatted(algorithm);
        return base + replay;
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
        // Fluent setup preserves this container; the calling test owns and closes the result.
        @SuppressWarnings("resource")
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

    private void grantAll(Connection admin, Engine engine) throws SQLException {
        if (engine == Engine.POSTGRESQL) {
            execute(admin, "GRANT ALL PRIVILEGES ON SCHEMA public TO chorus_user");
            execute(admin, "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO chorus_user");
        } else {
            execute(admin, "GRANT ALL PRIVILEGES ON chorus.* TO 'chorus_user'@'%'");
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

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
