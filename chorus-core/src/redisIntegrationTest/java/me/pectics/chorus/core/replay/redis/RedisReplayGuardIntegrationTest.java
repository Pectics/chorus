/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.6, 3.9, and 6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/redis/RedisReplayGuard.java
 * sync: Exercise real set/del semantics, ACL startup validation, TLS, and outage rejection.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.engine.DefaultChorusCoreEngine;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;
import me.pectics.chorus.core.node.ServerNodeConfiguration;
import me.pectics.chorus.core.node.ServerNodeRole;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuanceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

class RedisReplayGuardIntegrationTest {
    private static final String PASSWORD = "redis-integration-password";
    private static final String RESOURCE = "/me/pectics/chorus/core/replay/redis/tls/";
    private static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");

    @TempDir Path directory;

    @Test
    void registersConsumesAndRejectsReplayAgainstRealRedis() throws Exception {
        try (var redis = start(false)) {
            writePassword(PASSWORD);
            var settings =
                    settings(
                            redis.getMappedPort(6379),
                            ReplayGuardConfiguration.TlsMode.DISABLED,
                            null);
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            try (var first = new RedisReplayGuard(settings, "backend", clock);
                    var second = new RedisReplayGuard(settings, "backend", clock)) {
                Instant expiresAt = NOW.plusSeconds(15);
                first.register("gateway", "ticket", "backend", expiresAt)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                assertThatThrownBy(
                                () ->
                                        first.register("gateway", "ticket", "backend", expiresAt)
                                                .toCompletableFuture()
                                                .get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);

                var attempts =
                        List.of(
                                first.consume("gateway", "ticket", "backend", expiresAt)
                                        .toCompletableFuture(),
                                second.consume("gateway", "ticket", "backend", expiresAt)
                                        .toCompletableFuture());
                CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                        .get(10, TimeUnit.SECONDS);
                assertThat(attempts.stream().map(CompletableFuture::join).toList())
                        .containsExactlyInAnyOrder(
                                ReplayDecision.ACCEPTED, ReplayDecision.REPLAYED);
                assertThat(
                                first.consume("gateway", "missing", "backend", expiresAt)
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.REPLAYED);
            }
        }
    }

    @Test
    void rejectsWrongCredentialsAtStartup() throws Exception {
        try (var redis = start(false)) {
            writePassword("wrong-password");
            assertThatThrownBy(
                            () ->
                                    new RedisReplayGuard(
                                            settings(
                                                    redis.getMappedPort(6379),
                                                    ReplayGuardConfiguration.TlsMode.DISABLED,
                                                    null),
                                            "backend",
                                            Clock.fixed(NOW, ZoneOffset.UTC)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("credential/CA");
        }
    }

    @Test
    void namedAclUserHonorsCommandAndKeyPermissions() throws Exception {
        try (var redis = start(false)) {
            writePassword(PASSWORD);
            assertThat(
                            command(
                                    redis,
                                    "ACL",
                                    "SETUSER",
                                    "chorus",
                                    "on",
                                    ">" + PASSWORD,
                                    "~chorus:replay:*",
                                    "-@all",
                                    "+ping",
                                    "+set",
                                    "+del",
                                    "+select"))
                    .isEqualTo("OK");
            var settings =
                    settings(
                            redis.getMappedPort(6379),
                            ReplayGuardConfiguration.TlsMode.DISABLED,
                            null,
                            "chorus");
            var expiry = NOW.plusSeconds(15);
            try (var guard =
                    new RedisReplayGuard(settings, "backend", Clock.fixed(NOW, ZoneOffset.UTC))) {
                guard.register("gateway", "acl", "backend", expiry)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertThat(command(redis, "ACL", "SETUSER", "chorus", "-del")).isEqualTo("OK");
                assertThat(
                                guard.consume("gateway", "acl", "backend", expiry)
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThat(command(redis, "EXISTS", RedisReplayKey.encode("gateway", "acl")))
                        .isEqualTo("1");
                assertThat(command(redis, "ACL", "SETUSER", "chorus", "+del")).isEqualTo("OK");
                assertThat(
                                guard.consume("gateway", "acl", "backend", expiry)
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.ACCEPTED);

                assertThat(command(redis, "ACL", "SETUSER", "chorus", "-set")).isEqualTo("OK");
                assertThatThrownBy(
                                () ->
                                        guard.register("gateway", "no-set", "backend", expiry)
                                                .toCompletableFuture()
                                                .get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(RuntimeException.class);
                assertThat(
                                command(
                                        redis,
                                        "ACL",
                                        "SETUSER",
                                        "chorus",
                                        "+set",
                                        "resetkeys",
                                        "~unrelated:*"))
                        .isEqualTo("OK");
                assertThatThrownBy(
                                () ->
                                        guard.register("gateway", "wrong-prefix", "backend", expiry)
                                                .toCompletableFuture()
                                                .get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(RuntimeException.class);
                assertThat(
                                command(
                                        redis,
                                        "EXISTS",
                                        RedisReplayKey.encode("gateway", "wrong-prefix")))
                        .isEqualTo("0");
            }
        }
    }

    @Test
    void startupRejectsAnAclUserThatCannotPing() throws Exception {
        try (var redis = start(false)) {
            writePassword(PASSWORD);
            assertThat(
                            command(
                                    redis,
                                    "ACL",
                                    "SETUSER",
                                    "chorus",
                                    "on",
                                    ">" + PASSWORD,
                                    "~chorus:replay:*",
                                    "-@all",
                                    "+set",
                                    "+del"))
                    .isEqualTo("OK");
            assertThatThrownBy(
                            () ->
                                    new RedisReplayGuard(
                                            settings(
                                                    redis.getMappedPort(6379),
                                                    ReplayGuardConfiguration.TlsMode.DISABLED,
                                                    null,
                                                    "chorus"),
                                            "backend",
                                            Clock.fixed(NOW, ZoneOffset.UTC)))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void acknowledgedRegistrationWithALostReplyFailsIssuanceWithoutRetry() throws Exception {
        try (var redis = start(false);
                var proxy = new RedisReplyDroppingProxy(redis.getMappedPort(6379))) {
            writePassword(PASSWORD);
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var settings = settings(proxy.port(), ReplayGuardConfiguration.TlsMode.DISABLED, null);
            try (var guard = new RedisReplayGuard(settings, "gateway", clock);
                    var sender =
                            new DefaultChorusCoreEngine(
                                    new ServerNodeConfiguration("gateway", ServerNodeRole.GATEWAY),
                                    new TicketConfiguration(
                                            "chorus:ticket", 4096, 2048, Duration.ofSeconds(15)),
                                    new TicketIssuanceConfiguration(
                                            Duration.ofSeconds(15), TestSupport.issuancePolicy()),
                                    null,
                                    TestSupport.serverNodeRegistry(),
                                    null,
                                    TestSupport.signer(TestSupport.keyPair()),
                                    guard,
                                    clock)) {
                proxy.dropNext("SET");
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
                        .hasRootCauseMessage("REPLAY_REGISTRATION_FAILED");
                assertThat(proxy.setCalls()).isEqualTo(1);
                assertThat(command(redis, "DBSIZE")).isEqualTo("1");
            }
        }
    }

    @Test
    void acknowledgedConsumptionWithALostReplyRejectsAndNeverRestoresTheTicket() throws Exception {
        try (var redis = start(false);
                var proxy = new RedisReplyDroppingProxy(redis.getMappedPort(6379))) {
            writePassword(PASSWORD);
            var settings = settings(proxy.port(), ReplayGuardConfiguration.TlsMode.DISABLED, null);
            var expiry = NOW.plusSeconds(15);
            try (var guard =
                    new RedisReplayGuard(settings, "backend", Clock.fixed(NOW, ZoneOffset.UTC))) {
                guard.register("gateway", "lost-reply", "backend", expiry)
                        .toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                proxy.dropNext("DEL");
                assertThat(
                                guard.consume("gateway", "lost-reply", "backend", expiry)
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThat(proxy.deleteCalls()).isEqualTo(1);
                assertThat(command(redis, "EXISTS", RedisReplayKey.encode("gateway", "lost-reply")))
                        .isEqualTo("0");
                assertThat(
                                guard.consume("gateway", "lost-reply", "backend", expiry)
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.REPLAYED);
                assertThat(proxy.deleteCalls()).isEqualTo(2);
            }
        }
    }

    @Test
    void tlsWithConfiguredCaRegistersAndConsumes() throws Exception {
        try (var redis = start(true)) {
            writePassword(PASSWORD);
            Path ca = directory.resolve("ca.pem");
            Files.write(ca, resource("ca.pem"));
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            try (var guard =
                    new RedisReplayGuard(
                            settings(
                                    redis.getMappedPort(6379),
                                    ReplayGuardConfiguration.TlsMode.VERIFY_FULL,
                                    ca),
                            "backend",
                            clock)) {
                Instant expiresAt = NOW.plusSeconds(15);
                guard.register("gateway", "tls-ticket", "backend", expiresAt)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                assertThat(
                                guard.consume("gateway", "tls-ticket", "backend", expiresAt)
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.ACCEPTED);
            }
            assertThatThrownBy(
                            () ->
                                    new RedisReplayGuard(
                                            settings(
                                                    redis.getMappedPort(6379),
                                                    ReplayGuardConfiguration.TlsMode.VERIFY_FULL,
                                                    null),
                                            "backend",
                                            clock))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(
                            () ->
                                    new RedisReplayGuard(
                                            settings(
                                                    redis.getMappedPort(6379),
                                                    ReplayGuardConfiguration.TlsMode.DISABLED,
                                                    null),
                                            "backend",
                                            clock))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void stoppingRedisMakesConsumptionUnavailableAndRegistrationFail() throws Exception {
        var redis = start(false);
        try {
            writePassword(PASSWORD);
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            try (var guard =
                    new RedisReplayGuard(
                            settings(
                                    redis.getMappedPort(6379),
                                    ReplayGuardConfiguration.TlsMode.DISABLED,
                                    null),
                            "backend",
                            clock)) {
                Instant expiresAt = NOW.plusSeconds(15);
                guard.register("gateway", "outage-ticket", "backend", expiresAt)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                redis.stop();
                assertThat(
                                guard.consume("gateway", "outage-ticket", "backend", expiresAt)
                                        .toCompletableFuture()
                                        .get(5, TimeUnit.SECONDS))
                        .isEqualTo(ReplayDecision.UNAVAILABLE);
                assertThatThrownBy(
                                () ->
                                        guard.register(
                                                        "gateway",
                                                        "new-ticket",
                                                        "backend",
                                                        expiresAt)
                                                .toCompletableFuture()
                                                .get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(RuntimeException.class);
            }
        } finally {
            redis.close();
        }
    }

    private GenericContainer<?> start(boolean tls) throws IOException {
        // Fluent setup preserves this container; the calling test owns and closes the result.
        @SuppressWarnings("resource")
        var container =
                new GenericContainer<>(
                                DockerImageName.parse(System.getProperty("chorus.redis.image")))
                        .withExposedPorts(6379)
                        .withStartupTimeout(Duration.ofMinutes(3));
        if (tls) {
            container
                    .withCopyToContainer(Transferable.of(resource("ca.pem"), 0644), "/tls/ca.pem")
                    .withCopyToContainer(
                            Transferable.of(resource("server.pem"), 0644), "/tls/server.pem")
                    .withCopyToContainer(
                            Transferable.of(resource("server.key"), 0644), "/tls/server.key")
                    .withCommand(
                            "redis-server",
                            "--port",
                            "0",
                            "--tls-port",
                            "6379",
                            "--tls-cert-file",
                            "/tls/server.pem",
                            "--tls-key-file",
                            "/tls/server.key",
                            "--tls-ca-cert-file",
                            "/tls/ca.pem",
                            "--tls-auth-clients",
                            "no",
                            "--requirepass",
                            PASSWORD);
        } else {
            container.withCommand("redis-server", "--requirepass", PASSWORD);
        }
        container.start();
        return container;
    }

    private ReplayGuardConfiguration.Redis settings(
            int port, ReplayGuardConfiguration.TlsMode mode, Path ca) {
        return settings(port, mode, ca, "default");
    }

    private ReplayGuardConfiguration.Redis settings(
            int port, ReplayGuardConfiguration.TlsMode mode, Path ca, String username) {
        return new ReplayGuardConfiguration.Redis(
                "localhost",
                port,
                0,
                username,
                directory.resolve("password.txt"),
                new ReplayGuardConfiguration.Tls(mode, ca),
                new ReplayGuardConfiguration.Timeouts(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new ReplayGuardConfiguration.Pool(1));
    }

    private String command(GenericContainer<?> redis, String... arguments) throws Exception {
        var command =
                new java.util.ArrayList<>(
                        List.of("redis-cli", "--no-auth-warning", "-a", PASSWORD));
        command.addAll(List.of(arguments));
        var result = redis.execInContainer(command.toArray(String[]::new));
        assertThat(result.getExitCode()).isZero();
        return result.getStdout().strip();
    }

    private void writePassword(String password) throws IOException {
        Files.writeString(directory.resolve("password.txt"), password);
    }

    private byte[] resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(RESOURCE + name)) {
            if (input == null) {
                throw new IOException("Missing Redis integration resource " + name);
            }
            return input.readAllBytes();
        }
    }
}
