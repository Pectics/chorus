/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationLoader.java
 * sync: Verify all three file schemas run before persistent state or replacement snapshots are published.
 * @endcontext
 */
package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.config.CoreBootstrap;
import me.pectics.chorus.core.model.AuthenticatedIdentity;
import me.pectics.chorus.core.model.ReceiverLoginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreBootstrapTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(TestSupport.NOW, ZoneOffset.UTC);

    @BeforeEach
    void prepareFiles() throws Exception {
        var pair = TestSupport.keyPair();
        Files.write(directory.resolve("private.der"), pair.getPrivate().getEncoded());
        Files.write(directory.resolve("public.der"), pair.getPublic().getEncoded());
        Files.writeString(
                directory.resolve("servers.yml"),
                """
                servers:
                  gateway:
                    host: gateway.example.net
                    port: 25565
                  backend:
                    host: backend.example.net
                    port: 25565
                """);
        Files.writeString(
                directory.resolve("trusted-issuers.yml"),
                """
                trusted-issuers:
                  gateway:
                    keys:
                      - kid: key-1
                        public-key-file: public.der
                    allowed-auth-types: [MOJANG, OFFLINE_AUTH, OFFLINE_RAW]
                    allowed-providers: [mojang, local]
                    allowed-targets: [backend]
                """);
    }

    private String configuration(String node, boolean sender, boolean receiver) {
        return """
                node:
                  id: %s
                  role: PEER
                  capabilities:
                    sender: %s
                    receiver: %s
                cookie-key: chorus:ticket
                crypto:
                  algorithm: ED25519
                  key-id: key-1
                  ticket-ttl: 15s
                  max-ticket-lifetime: 15s
                  clock-skew: 2s
                  max-ticket-bytes: 4096
                  max-property-bytes: 2048
                  private-key-file: private.der
                  public-key-file: public.der
                registry:
                  mode: STATIC
                  static:
                    servers-file: servers.yml
                    trust-file: trusted-issuers.yml
                verification:
                  require-transfer-intent: true
                  identity-strategy: ADOPT_UPSTREAM
                  ip-binding-mode: DISABLED
                replay:
                  mode: LOCAL_SQLITE
                  file: state/%s.db
                  capacity: 100
                  busy-timeout: 500ms
                %s
                """
                .formatted(
                        node,
                        sender,
                        receiver,
                        node,
                        sender
                                ? """
                issuance:
                  allowed-auth-types: [MOJANG, OFFLINE_AUTH, OFFLINE_RAW]
                  allowed-providers: [mojang, local]
                  allowed-targets: [backend]
                """
                                : "");
    }

    private Path write(String name, String config) throws Exception {
        return Files.writeString(directory.resolve(name), config);
    }

    @Test
    void configuredSenderToReceiverInheritsMojangIdentityAndRejectsReplayAfterRestart()
            throws Exception {
        Path senderFile = write("gateway.yml", configuration("gateway", true, false));
        Path receiverFile = write("backend.yml", configuration("backend", false, true));
        byte[] payload;
        try (var sender = CoreBootstrap.open(senderFile, clock)) {
            var ticket =
                    sender.issueTicket(
                                    new IssueTicketRequest(
                                            "backend",
                                            new AuthenticatedIdentity(
                                                    TestSupport.identity(), "gateway"),
                                            Map.of()))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            payload = ticket.cookiePayload();
            assertThat(ticket.targetServer().host()).isEqualTo("backend.example.net");
        }
        Files.delete(directory.resolve("private.der"));
        var request =
                new VerifyTicketRequest(payload, new ReceiverLoginContext("Notch", true, null));
        try (var receiver = CoreBootstrap.open(receiverFile, clock)) {
            var result =
                    receiver.verifyAndResolve(request)
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            assertThat(result.resolvedProfile().finalUuid()).isEqualTo(TestSupport.UUID_MOJANG);
            assertThat(result.resolvedProfile().properties())
                    .isEqualTo(TestSupport.identity().properties());
            assertThat(result.retainedIdentity().claim()).isEqualTo(TestSupport.identity());
        }
        try (var receiver = CoreBootstrap.open(receiverFile, clock)) {
            assertThatThrownBy(
                            () -> receiver.verifyAndResolve(request).toCompletableFuture().join())
                    .hasCauseInstanceOf(TicketRejectedException.class)
                    .hasRootCauseMessage("REPLAYED");
        }
        assertThat(directory.resolve("state/gateway.db")).doesNotExist();
    }

    @Test
    void staticReloadPublishesOnlyACompleteValidSnapshotAndDoesNotAgeOutStaticApproval()
            throws Exception {
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        var registries = CoreBootstrap.loadRegistries(servers, trust, clock);
        var previous = registries.snapshot();
        Files.writeString(servers, Files.readString(servers).replace("25565", "25566"));
        CoreBootstrap.reloadRegistries(
                registries, servers, trust, Clock.offset(clock, java.time.Duration.ofDays(30)));
        assertThat(registries.find("backend").orElseThrow().port()).isEqualTo(25566);
        assertThat(previous.servers().get("backend").port()).isEqualTo(25565);
        assertThat(registries.snapshot().stale()).isFalse();
        var approved = registries.snapshot();
        Files.writeString(trust, Files.readString(trust).replace("[backend]", "[unknown]"));
        assertThatThrownBy(() -> CoreBootstrap.reloadRegistries(registries, servers, trust, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registries.snapshot()).isSameAs(approved);
        Files.writeString(trust, "trusted-issuers: {gateway: {keys: []}}");
        assertThatThrownBy(() -> CoreBootstrap.reloadRegistries(registries, servers, trust, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registries.snapshot()).isSameAs(approved);
    }

    @Test
    void rejectsInvalidLimitsMissingIssuanceUnsupportedModesAndUnknownFieldsBeforeOpeningState()
            throws Exception {
        String base = configuration("backend", false, true);
        for (String invalid :
                java.util.List.of(
                        base.replace("ticket-ttl: 15s", "ticket-ttl: 0s"),
                        base.replace("ticket-ttl: 15s", "ticket-ttl: 500ms"),
                        base.replace("max-ticket-lifetime: 15s", "max-ticket-lifetime: 14s"),
                        base.replace("max-ticket-bytes: 4096", "max-ticket-bytes: 5121"),
                        base.replace("max-property-bytes: 2048", "max-property-bytes: 4097"),
                        base.replace("clock-skew: 2s", "clock-skew: -PT1S"),
                        base.replace("ticket-ttl: 15s", "ticket-ttl: 999999999999999999999h"),
                        base.replace("busy-timeout: 500ms", "busy-timeout: 0ms"),
                        base.replace("busy-timeout: 500ms", "busy-timeout: 2147483648ms"),
                        base.replace("mode: STATIC", "mode: SHARED_SQL"),
                        base.replace("mode: LOCAL_SQLITE", "mode: LOCAL_MEMORY"),
                        base.replace(
                                "identity-strategy: ADOPT_UPSTREAM",
                                "identity-strategy: FEDERATED_NAMESPACE"),
                        base.replace("algorithm: ED25519", "algorithm: HMAC"),
                        base.replace(
                                "algorithm: ED25519",
                                "algorithm: ED25519\n  compress-payload: true"),
                        base.replace(
                                "require-transfer-intent: true",
                                "require-transfer-intent: yes-please"),
                        base + "unrecognized-option: true\n")) {
            Path file = write("invalid.yml", invalid);
            assertThatThrownBy(() -> CoreBootstrap.open(file, clock))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(directory.resolve("state/backend.db")).doesNotExist();
        }
        String sender = configuration("gateway", true, false);
        assertThatThrownBy(
                        () ->
                                CoreBootstrap.open(
                                        write(
                                                "missing-policy.yml",
                                                sender.substring(0, sender.indexOf("issuance:"))),
                                        clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyReferencedFileIsCheckedAgainstItsOwnCompleteSchemaBeforeOpeningState()
            throws Exception {
        Path main = write("chorus.yml", configuration("backend", false, true));
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        String original = Files.readString(servers);
        Files.writeString(servers, "trusted-issuers: {}");
        assertThatThrownBy(() -> CoreBootstrap.open(main, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(servers.toString())
                .hasMessageContaining("Schema validation failed");
        Files.writeString(servers, original);
        Files.writeString(trust, "servers: {}");
        assertThatThrownBy(() -> CoreBootstrap.open(main, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(trust.toString())
                .hasMessageContaining("Schema validation failed");
        assertThat(directory.resolve("state")).doesNotExist();
    }

    @Test
    void mainSchemaChecksPresentInactiveSectionsBeforeAnyReferencedFileIsRead() throws Exception {
        Files.delete(directory.resolve("servers.yml"));
        Files.delete(directory.resolve("trusted-issuers.yml"));
        Path main =
                write(
                        "chorus.yml",
                        configuration("backend", false, true) + "issuance: {typo: true}\n");
        assertThatThrownBy(() -> CoreBootstrap.open(main, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/issuance")
                .hasMessageContaining("Schema validation failed");
        assertThat(directory.resolve("state")).doesNotExist();
    }

    @Test
    void convertsSchemaApprovedDurationSpellingsIntoEquivalentRuntimeValues() throws Exception {
        for (String duration : java.util.List.of("15000ms", "PT15.000S", "pt15s", "-PT-15S")) {
            Path main =
                    write(
                            "chorus.yml",
                            configuration("backend", false, true)
                                    .replace("ticket-ttl: 15s", "ticket-ttl: " + duration));
            try (var core = CoreBootstrap.open(main, clock)) {
                assertThat(core.getCookieKey()).isEqualTo("chorus:ticket");
            }
        }
    }

    @Test
    void schemaFailureKeepsBothApprovedDirectoriesUnchanged() throws Exception {
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        var registries = CoreBootstrap.loadRegistries(servers, trust, clock);
        var previous = registries.snapshot();
        var key = registries.find("gateway", "key-1").orElseThrow();
        Files.writeString(servers, Files.readString(servers).replace("25565", "25566"));
        Files.writeString(
                trust, Files.readString(trust).replace("[mojang, local]", "[mojang, mojang]"));
        assertThatThrownBy(() -> CoreBootstrap.reloadRegistries(registries, servers, trust, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-providers (uniqueItems)");
        assertThat(registries.snapshot()).isSameAs(previous);
        assertThat(registries.find("gateway", "key-1")).contains(key);
    }

    @Test
    void rejectsYamlDuplicatesCustomTagsAndAliasesWithoutPublishing() throws Exception {
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        var registries = CoreBootstrap.loadRegistries(servers, trust, clock);
        var approved = registries.snapshot();
        for (String invalid :
                java.util.List.of(
                        "servers: {}\nservers: {}\n",
                        "servers: !!java.util.HashMap {}\n",
                        "servers: {gateway: &node {host: localhost, port: 25565}, backend: *node}\n",
                        "servers: {gateway: {host: localhost, port: 70000}}\n")) {
            Files.writeString(servers, invalid);
            assertThatThrownBy(
                            () -> CoreBootstrap.reloadRegistries(registries, servers, trust, clock))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(registries.snapshot()).isSameAs(approved);
        }
    }

    @Test
    void brokenApprovedKeyDoesNotReplaceTheCurrentTrustSnapshot() throws Exception {
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        var registries = CoreBootstrap.loadRegistries(servers, trust, clock);
        var approved = registries.find("gateway", "key-1").orElseThrow();
        Files.writeString(directory.resolve("public.der"), "broken key");
        assertThatThrownBy(() -> CoreBootstrap.reloadRegistries(registries, servers, trust, clock))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(registries.find("gateway", "key-1")).contains(approved);
    }
}
