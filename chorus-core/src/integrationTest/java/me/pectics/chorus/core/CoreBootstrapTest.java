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

import java.io.IOException;
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
import me.pectics.chorus.core.config.model.NodeRole;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketCodec;
import me.pectics.chorus.core.model.AuthenticatedIdentity;
import me.pectics.chorus.core.model.ReceiverLoginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                gateway:
                  address: gateway.example.net:25565
                backend:
                  address: backend.example.net:25565
                next-backend:
                  address: next-backend.example.net:25565
                """);
        Files.writeString(
                directory.resolve("trusted-issuers.yml"),
                """
                gateway:
                  keys:
                    - public.der
                  allowed-auth-schemes: [MOJANG, OFFLINE_AUTH, OFFLINE_RAW]
                  allowed-providers: [mojang, local]
                """);
    }

    private String configuration(String node, NodeRole role) {
        String issuance =
                role.sender()
                        ? """
                issuance:
                  ttl: 15s
                  allowed-auth-schemes: [MOJANG, OFFLINE_AUTH, OFFLINE_RAW]
                  allowed-providers: [mojang, local]
                  allowed-targets: [backend]
                """
                        : "";
        return """
                node:
                  id: %s
                  role: %s
                ticket:
                  cookie-key: chorus:ticket
                  max-payload-bytes: 4096
                  max-property-bytes: 2048
                  compression: NONE
                crypto:
                  algorithm: ED25519
                  private-key: private.der
                  public-key: public.der
                registry:
                  provider: CONFIG
                  config:
                    servers-path: servers.yml
                %s
                verification:
                  trusted-issuers-path: trusted-issuers.yml
                  max-lifetime: 15s
                  clock-skew: 2s
                  enforce-transfer-intent: true
                  identity-policy: ADOPT_UPSTREAM
                  ip-binding: DISABLED
                replay-guard:
                  backend: LOCAL
                  local:
                    path: state/%s.db
                    max-entries: 100
                    busy-timeout: 500ms
                """
                .formatted(node, role, issuance, node);
    }

    private Path write(String name, String config) throws Exception {
        return Files.writeString(directory.resolve(name), config);
    }

    @ParameterizedTest
    @CsvSource({
        "NONE, NONE", "NONE, ZSTD", "NONE, GZIP",
        "ZSTD, NONE", "ZSTD, ZSTD", "ZSTD, GZIP",
        "GZIP, NONE", "GZIP, ZSTD", "GZIP, GZIP",
        "DEFAULT, DEFAULT"
    })
    void configuredSenderToReceiverInheritsMojangIdentityAndRejectsReplayAfterRestart(
            String senderCompression, String receiverCompression) throws Exception {
        Path senderFile =
                write(
                        "gateway.yml",
                        withCompression(
                                configuration("gateway", NodeRole.GATEWAY), senderCompression));
        Path receiverFile =
                write(
                        "backend.yml",
                        withCompression(
                                configuration("backend", NodeRole.BACKEND), receiverCompression));
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
            if (senderCompression.equals("NONE")) {
                assertThat(payload).startsWith((byte) 'e', (byte) 'y');
            } else {
                assertThat(payload)
                        .startsWith(
                                (byte) 'C',
                                (byte) 'H',
                                (byte) 'R',
                                (byte) 'S',
                                (byte) 1,
                                (byte) (senderCompression.equals("GZIP") ? 2 : 1));
            }
            assertThat(ticket.targetServer().host()).isEqualTo("backend.example.net");
            assertThat(ticket.expiresAt()).isEqualTo(TestSupport.NOW.plusSeconds(15));
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

    private String withCompression(String configuration, String compression) {
        return configuration.replace(
                "  compression: NONE\n",
                compression.equals("DEFAULT") ? "" : "  compression: " + compression + "\n");
    }

    @Test
    void configuredPeerCanReceiveAndForwardUsingOnlyItsRole() throws Exception {
        Path senderFile = write("gateway.yml", configuration("gateway", NodeRole.GATEWAY));
        Path peerFile =
                write(
                        "peer.yml",
                        configuration("backend", NodeRole.PEER)
                                .replace(
                                        "allowed-targets: [backend]",
                                        "allowed-targets: [next-backend]"));
        try (var sender = CoreBootstrap.open(senderFile, clock);
                var peer = CoreBootstrap.open(peerFile, clock)) {
            var issued =
                    sender.issueTicket(
                                    new IssueTicketRequest(
                                            "backend",
                                            new AuthenticatedIdentity(
                                                    TestSupport.identity(), "gateway"),
                                            Map.of()))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            var accepted =
                    peer.verifyAndResolve(
                                    new VerifyTicketRequest(
                                            issued.cookiePayload(),
                                            new ReceiverLoginContext("Notch", true, null)))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            var forwarded =
                    peer.issueTicket(
                                    new IssueTicketRequest(
                                            "next-backend", accepted.retainedIdentity(), Map.of()))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            assertThat(forwarded.targetServer().nodeId()).isEqualTo("next-backend");
            assertThat(accepted.retainedIdentity().claim()).isEqualTo(TestSupport.identity());
        }
    }

    @Test
    void serverListReloadDoesNotReadTrustAndDoesNotAgeOutConfigApproval() throws Exception {
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        var registry = CoreBootstrap.loadServerNodeRegistry(servers, clock);
        var issuers = CoreBootstrap.loadTrustedIssuers(trust);
        var previous = registry.snapshot();
        var approval = issuers.findByIssuer("gateway").orElseThrow();
        Files.writeString(servers, Files.readString(servers).replace("25565", "25566"));
        Files.writeString(trust, "gateway: {keys: []}");
        Files.delete(directory.resolve("public.der"));
        CoreBootstrap.reloadServerNodeRegistry(
                registry, servers, Clock.offset(clock, java.time.Duration.ofDays(30)));
        assertThat(registry.find("backend").orElseThrow().port()).isEqualTo(25566);
        assertThat(previous.servers().get("backend").port()).isEqualTo(25565);
        assertThat(registry.snapshot().stale()).isFalse();
        assertThat(issuers.findByIssuer("gateway")).containsSame(approval);
        assertThatThrownBy(() -> CoreBootstrap.reloadTrustedIssuers(issuers, trust))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(issuers.findByIssuer("gateway")).containsSame(approval);
        var approved = registry.snapshot();
        Files.writeString(servers, "backend: {address: localhost:70000}");
        assertThatThrownBy(() -> CoreBootstrap.reloadServerNodeRegistry(registry, servers, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.snapshot()).isSameAs(approved);
    }

    @Test
    void trustCanBeLoadedAndReloadedWithoutAnyServerListOrSigningKey() throws Exception {
        Path trust = directory.resolve("trusted-issuers.yml");
        Files.delete(directory.resolve("servers.yml"));
        Files.delete(directory.resolve("private.der"));
        var issuers = CoreBootstrap.loadTrustedIssuers(trust);
        var approved = issuers.findByIssuer("gateway").orElseThrow();
        assertThat(approved.algorithm()).isEqualTo(SignatureAlgorithm.ED25519);
        Files.writeString(
                trust, Files.readString(trust).replace("  keys:", "  enabled: false\n  keys:"));
        CoreBootstrap.reloadTrustedIssuers(issuers, trust);
        assertThat(issuers.findByIssuer("gateway").orElseThrow().enabled()).isFalse();
        assertThat(approved.enabled()).isTrue();
        assertThat(directory.resolve("servers.yml")).doesNotExist();
        assertThat(directory.resolve("private.der")).doesNotExist();
    }

    @Test
    void serverListMembershipDoesNotApproveAnIssuerOrConsumeItsTicket() throws Exception {
        Path senderFile = write("gateway.yml", configuration("gateway", NodeRole.GATEWAY));
        Path receiverFile = write("backend.yml", configuration("backend", NodeRole.BACKEND));
        byte[] payload;
        try (var sender = CoreBootstrap.open(senderFile, clock)) {
            payload =
                    sender.issueTicket(
                                    new IssueTicketRequest(
                                            "backend",
                                            new AuthenticatedIdentity(
                                                    TestSupport.identity(), "gateway"),
                                            Map.of()))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS)
                            .cookiePayload();
        }
        Path trust = directory.resolve("trusted-issuers.yml");
        String approvals = Files.readString(trust);
        Files.writeString(trust, "{}\n");
        var request =
                new VerifyTicketRequest(payload, new ReceiverLoginContext("Notch", true, null));
        try (var receiver = CoreBootstrap.open(receiverFile, clock)) {
            assertThat(receiver.getServerNodeRegistry().find("gateway")).isPresent();
            assertThatThrownBy(
                            () -> receiver.verifyAndResolve(request).toCompletableFuture().join())
                    .hasRootCauseMessage("UNKNOWN_ISSUER");
        }
        Files.writeString(trust, approvals);
        try (var receiver = CoreBootstrap.open(receiverFile, clock)) {
            assertThat(
                            receiver.verifyAndResolve(request)
                                    .toCompletableFuture()
                                    .get(5, TimeUnit.SECONDS)
                                    .resolvedProfile()
                                    .finalUuid())
                    .isEqualTo(TestSupport.UUID_MOJANG);
        }
    }

    @Test
    void hmacLocalConfigurationDoesNotChangeOmittedEd25519TrustAlgorithm() throws Exception {
        byte[] payload =
                new TicketCodec(4096, 2048)
                        .encode(
                                TestSupport.claims(),
                                Ed25519.loadSigner(
                                        directory.resolve("private.der"),
                                        directory.resolve("public.der")));
        Path receiverFile =
                write(
                        "backend.yml",
                        configuration("backend", NodeRole.BACKEND)
                                .replace(
                                        "algorithm: ED25519\n  private-key: private.der\n  public-key: public.der",
                                        "algorithm: HMAC_SHA256"));
        Files.delete(directory.resolve("private.der"));
        try (var receiver = CoreBootstrap.open(receiverFile, clock)) {
            var result =
                    receiver.verifyAndResolve(
                                    new VerifyTicketRequest(
                                            payload, new ReceiverLoginContext("Notch", true, null)))
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            assertThat(result.resolvedProfile().finalUuid()).isEqualTo(TestSupport.UUID_MOJANG);
        }
        assertThat(directory.resolve("keys")).doesNotExist();
    }

    @Test
    void rejectsInvalidLimitsMissingIssuanceUnsupportedModesAndUnknownFieldsBeforeOpeningState()
            throws Exception {
        String base = configuration("backend", NodeRole.PEER);
        for (String invalid :
                java.util.List.of(
                        base.replace("ttl: 15s", "ttl: 0s"),
                        base.replace("ttl: 15s", "ttl: 500ms"),
                        base.replace("max-lifetime: 15s", "max-lifetime: 14s"),
                        base.replace("max-payload-bytes: 4096", "max-payload-bytes: 5121"),
                        base.replace("max-property-bytes: 2048", "max-property-bytes: 4097"),
                        base.replace("clock-skew: 2s", "clock-skew: -PT1S"),
                        base.replace("ttl: 15s", "ttl: 999999999999999999999h"),
                        base.replace("busy-timeout: 500ms", "busy-timeout: 0ms"),
                        base.replace("busy-timeout: 500ms", "busy-timeout: 2147483648ms"),
                        base.replace("provider: CONFIG", "provider: SHARED_SQL"),
                        base.replace("provider: CONFIG", "provider: STATIC"),
                        base.replace("config:", "static:"),
                        base.replace("backend: LOCAL", "backend: UNKNOWN"),
                        base.replace(
                                "identity-policy: ADOPT_UPSTREAM",
                                "identity-policy: FEDERATED_NAMESPACE"),
                        base.replace("algorithm: ED25519", "algorithm: HMAC"),
                        base.replace(
                                "algorithm: ED25519",
                                "algorithm: ED25519\n  compress-payload: true"),
                        base.replace(
                                "enforce-transfer-intent: true",
                                "enforce-transfer-intent: yes-please"),
                        base + "unrecognized-option: true\n")) {
            Path file = write("invalid.yml", invalid);
            assertThatThrownBy(() -> CoreBootstrap.open(file, clock))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(directory.resolve("state/backend.db")).doesNotExist();
        }
        String sender = configuration("gateway", NodeRole.GATEWAY);
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
    void parsesBracketedIpv6AddressesWithoutResolvingHosts() throws Exception {
        Path servers = directory.resolve("servers.yml");
        Files.writeString(
                servers,
                Files.readString(servers)
                        .replace(
                                "address: backend.example.net:25565",
                                "address: \"[2001:db8::1]:25566\""));
        var registry = CoreBootstrap.loadServerNodeRegistry(servers, clock);
        var backend = registry.find("backend").orElseThrow();
        assertThat(backend.host()).isEqualTo("2001:db8::1");
        assertThat(backend.port()).isEqualTo(25566);
    }

    @Test
    void rejectsTodoOptionsBeforeReadingReferencedFilesOrOpeningState() throws Exception {
        Files.delete(directory.resolve("servers.yml"));
        Files.delete(directory.resolve("trusted-issuers.yml"));
        String base = configuration("backend", NodeRole.BACKEND);
        Path file = write("unsupported.yml", base.replace("backend: LOCAL", "backend: REDIS"));
        assertThatThrownBy(() -> CoreBootstrap.open(file, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not implemented");
        Path registryReplay =
                write("registry-replay.yml", base.replace("backend: LOCAL", "backend: REGISTRY"));
        assertThatThrownBy(() -> CoreBootstrap.open(registryReplay, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schema validation failed");
        assertThat(directory.resolve("state")).doesNotExist();
    }

    @Test
    void everyReferencedFileIsCheckedAgainstItsOwnCompleteSchemaBeforeOpeningState()
            throws Exception {
        Path main = write("chorus.yml", configuration("backend", NodeRole.BACKEND));
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        String original = Files.readString(servers);
        Files.writeString(
                servers,
                "gateway: {key: public.der, allowed-auth-schemes: [MOJANG], allowed-providers: [mojang]}");
        assertThatThrownBy(() -> CoreBootstrap.open(main, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(servers.toString())
                .hasMessageContaining("Schema validation failed");
        Files.writeString(servers, original);
        Files.writeString(trust, "backend: {address: localhost:25565}");
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
                        configuration("backend", NodeRole.BACKEND) + "issuance: {typo: true}\n");
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
                            configuration("backend", NodeRole.BACKEND)
                                    .replace("max-lifetime: 15s", "max-lifetime: " + duration));
            try (var core = CoreBootstrap.open(main, clock)) {
                assertThat(core.getCookieKey()).isEqualTo("chorus:ticket");
            }
        }
    }

    @Test
    void trustSchemaFailureRetainsApprovalsWithoutBlockingServerListReload() throws Exception {
        Path servers = directory.resolve("servers.yml");
        Path trust = directory.resolve("trusted-issuers.yml");
        var registry = CoreBootstrap.loadServerNodeRegistry(servers, clock);
        var issuers = CoreBootstrap.loadTrustedIssuers(trust);
        var previous = registry.snapshot();
        var approval = issuers.findByIssuer("gateway");
        Files.writeString(servers, Files.readString(servers).replace("25565", "25566"));
        Files.writeString(
                trust, Files.readString(trust).replace("[mojang, local]", "[mojang, mojang]"));
        assertThatThrownBy(() -> CoreBootstrap.reloadTrustedIssuers(issuers, trust))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-providers (uniqueItems)");
        assertThat(registry.snapshot()).isSameAs(previous);
        assertThat(issuers.findByIssuer("gateway")).isEqualTo(approval);
        CoreBootstrap.reloadServerNodeRegistry(registry, servers, clock);
        assertThat(registry.find("backend").orElseThrow().port()).isEqualTo(25566);
        assertThat(issuers.findByIssuer("gateway")).isEqualTo(approval);
    }

    @Test
    void rejectsYamlDuplicatesCustomTagsAndAliasesWithoutPublishing() throws Exception {
        Path servers = directory.resolve("servers.yml");
        var registry = CoreBootstrap.loadServerNodeRegistry(servers, clock);
        var approved = registry.snapshot();
        for (String invalid :
                java.util.List.of(
                        "backend: {}\nbackend: {}\n",
                        "!!java.util.HashMap {}\n",
                        "gateway: &node {address: localhost:25565}\nbackend: *node\n",
                        "gateway: {address: localhost:70000}\n")) {
            Files.writeString(servers, invalid);
            assertThatThrownBy(
                            () -> CoreBootstrap.reloadServerNodeRegistry(registry, servers, clock))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.snapshot()).isSameAs(approved);
        }
    }

    @Test
    void singleKeyPathLoadsTheSameApprovalAsAOneElementList() throws Exception {
        Path trust = directory.resolve("trusted-issuers.yml");
        var issuers = CoreBootstrap.loadTrustedIssuers(trust);
        var original = issuers.findByIssuer("gateway").orElseThrow();
        Files.writeString(
                trust,
                Files.readString(trust).replace("keys:\n    - public.der", "key: public.der"));
        CoreBootstrap.reloadTrustedIssuers(issuers, trust);
        assertThat(issuers.findByIssuer("gateway")).contains(original);
    }

    @Test
    void keysOverrideSingleKeyWithoutReadingItOrFallingBackOnLoadFailure() throws Exception {
        Path trust = directory.resolve("trusted-issuers.yml");
        Path preferredFile = directory.resolve("preferred.der");
        var preferred = TestSupport.keyPair();
        Files.write(preferredFile, preferred.getPublic().getEncoded());
        Files.writeString(
                trust,
                Files.readString(trust)
                        .replace(
                                "keys:\n    - public.der",
                                "key: missing.der\n  keys:\n    - preferred.der"));
        var issuers = CoreBootstrap.loadTrustedIssuers(trust);
        var approved = issuers.findByIssuer("gateway").orElseThrow();
        assertThat(approved.verifiers())
                .containsExactly(new Ed25519.Verifier(preferred.getPublic()));
        Files.writeString(
                trust, Files.readString(trust).replace("key: missing.der", "key: public.der"));
        Files.delete(preferredFile);
        assertThatThrownBy(() -> CoreBootstrap.reloadTrustedIssuers(issuers, trust))
                .isInstanceOf(IOException.class);
        assertThat(issuers.findByIssuer("gateway").orElseThrow()).isSameAs(approved);
    }

    @Test
    void loadsMultipleIssuerKeysAndRejectsDuplicatePublicKeysWithoutReplacingApprovals()
            throws Exception {
        Path trust = directory.resolve("trusted-issuers.yml");
        Files.write(
                directory.resolve("rotated.der"), TestSupport.keyPair().getPublic().getEncoded());
        Files.writeString(
                trust,
                Files.readString(trust).replace("- public.der", "- public.der\n    - rotated.der"));
        var issuers = CoreBootstrap.loadTrustedIssuers(trust);
        var approved = issuers.findByIssuer("gateway").orElseThrow();
        assertThat(approved.verifiers()).hasSize(2);
        assertThat(approved.enabled()).isTrue();
        Files.writeString(
                trust, Files.readString(trust).replace("  keys:", "  enabled: false\n  keys:"));
        CoreBootstrap.reloadTrustedIssuers(issuers, trust);
        var disabled = issuers.findByIssuer("gateway").orElseThrow();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.verifiers()).containsExactlyElementsOf(approved.verifiers());
        Files.write(
                directory.resolve("rotated.der"),
                Files.readAllBytes(directory.resolve("public.der")));
        assertThatThrownBy(() -> CoreBootstrap.reloadTrustedIssuers(issuers, trust))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct approved keys");
        assertThat(issuers.findByIssuer("gateway").orElseThrow()).isSameAs(disabled);
    }

    @Test
    void brokenApprovedKeyDoesNotReplaceTheCurrentTrustSnapshot() throws Exception {
        Path trust = directory.resolve("trusted-issuers.yml");
        var issuers = CoreBootstrap.loadTrustedIssuers(trust);
        var approved = issuers.findByIssuer("gateway");
        Files.writeString(directory.resolve("public.der"), "broken key");
        assertThatThrownBy(() -> CoreBootstrap.reloadTrustedIssuers(issuers, trust))
                .isInstanceOf(GeneralSecurityException.class);
        assertThat(issuers.findByIssuer("gateway")).isEqualTo(approved);
    }
}
