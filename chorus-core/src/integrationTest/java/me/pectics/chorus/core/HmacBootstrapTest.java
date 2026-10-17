/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.7 and 3.9
 * sync: Verify first-start generation, manual file distribution, trust reload, and durable replay checks.
 * @endcontext
 */
package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.config.CoreBootstrap;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.model.AuthenticatedIdentity;
import me.pectics.chorus.core.model.ReceiverLoginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacBootstrapTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(TestSupport.NOW, ZoneOffset.UTC);

    private Path deployment(String name, String role) throws Exception {
        Path base = Files.createDirectories(directory.resolve(name));
        Files.writeString(
                base.resolve("servers.yml"),
                """
                gateway:
                  address: gateway.example.net:25565
                backend:
                  address: backend.example.net:25565
                """);
        Files.createDirectories(base.resolve("trust"));
        Files.writeString(base.resolve("trust/issuers.yml"), "{}\n");
        return Files.writeString(
                base.resolve("chorus.yml"),
                """
                node:
                  id: %s
                  role: %s
                ticket:
                  cookie-key: chorus:ticket
                  max-payload-bytes: 4096
                  max-property-bytes: 2048
                crypto:
                  algorithm: HMAC_SHA256
                  key: keys/shared.key
                registry:
                  provider: CONFIG
                  config:
                    servers-path: servers.yml
                    issuers-path: trust/issuers.yml
                issuance:
                  ttl: 15s
                  allowed-auth-schemes: [MOJANG]
                  allowed-providers: [mojang]
                  allowed-targets: [backend]
                verification:
                  max-lifetime: 15s
                  clock-skew: 2s
                  enforce-transfer-intent: true
                  identity-policy: ADOPT_UPSTREAM
                replay-guard:
                  backend: LOCAL
                  local:
                    path: state/replay.db
                    max-entries: 100
                    busy-timeout: 500ms
                """
                        .formatted(name, role));
    }

    private void trust(Path config, String keyPath) throws Exception {
        Files.writeString(
                config.getParent().resolve("trust/issuers.yml"),
                """
                gateway:
                  algorithm: HMAC_SHA256
                  key: %s
                  allowed-auth-schemes: [MOJANG]
                  allowed-providers: [mojang]
                """
                        .formatted(keyPath));
    }

    @Test
    void generatesOnceAndVerifiesOnAnotherNodeAfterCopyingOnlyTheSharedKey() throws Exception {
        Path senderFile = deployment("gateway", "GATEWAY");
        Path receiverFile = deployment("backend", "BACKEND");
        Path senderSecret = senderFile.getParent().resolve("keys/shared.key");
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
            assertThat(payload)
                    .startsWith((byte) 'C', (byte) 'H', (byte) 'R', (byte) 'S', (byte) 1, (byte) 1);
        }
        byte[] original = Files.readAllBytes(senderSecret);
        try (var sender = CoreBootstrap.open(senderFile, clock)) {
            assertThat(sender.getCookieKey()).isEqualTo("chorus:ticket");
        }
        assertThat(Files.readAllBytes(senderSecret)).isEqualTo(original);
        Path receiverSecret = receiverFile.getParent().resolve("trust/gateway.key");
        Files.copy(senderSecret, receiverSecret);
        trust(receiverFile, "gateway.key");
        Files.writeString(
                receiverFile,
                Files.readString(receiverFile).replace("  key: keys/shared.key\n", ""));
        var request =
                new VerifyTicketRequest(payload, new ReceiverLoginContext("Notch", true, null));
        try (var receiver = CoreBootstrap.open(receiverFile, clock)) {
            var result =
                    receiver.verifyAndResolve(request)
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
            assertThat(result.resolvedProfile().finalUuid()).isEqualTo(TestSupport.UUID_MOJANG);
            assertThat(result.retainedIdentity().claim()).isEqualTo(TestSupport.identity());
        }
        try (var receiver = CoreBootstrap.open(receiverFile, clock)) {
            assertThatThrownBy(
                            () -> receiver.verifyAndResolve(request).toCompletableFuture().join())
                    .hasCauseInstanceOf(TicketRejectedException.class)
                    .satisfies(
                            failure ->
                                    assertThat(
                                                    ((TicketRejectedException) failure.getCause())
                                                            .code())
                                            .isEqualTo(TicketError.REPLAYED));
        }
        assertThat(receiverFile.getParent().resolve("keys")).doesNotExist();
    }

    @Test
    void peerCanApproveItsOwnSecretGeneratedDuringTheSameStartup() throws Exception {
        Path file = deployment("gateway", "PEER");
        trust(file, "../keys/shared.key");
        try (var peer = CoreBootstrap.open(file, clock)) {
            assertThat(peer.getServerRegistry().find("backend")).isPresent();
        }
        assertThat(file.getParent().resolve("keys/shared.key")).exists();
    }

    @Test
    void missingOrInvalidReceiverTrustKeysNeverGenerateSecretsOrOpenReplayState() throws Exception {
        Path file = deployment("backend", "BACKEND");
        trust(file, "missing/shared.key");
        assertThatThrownBy(() -> CoreBootstrap.open(file, clock)).isInstanceOf(IOException.class);
        assertThat(file.getParent().resolve("keys")).doesNotExist();
        assertThat(file.getParent().resolve("trust/missing")).doesNotExist();
        assertThat(file.getParent().resolve("state")).doesNotExist();
        Path invalid = file.getParent().resolve("trust/broken.key");
        Files.writeString(invalid, "not a key", StandardCharsets.US_ASCII);
        trust(file, "broken.key");
        assertThatThrownBy(() -> CoreBootstrap.open(file, clock)).isInstanceOf(IOException.class);
        assertThat(Files.readString(invalid)).isEqualTo("not a key");
        assertThat(file.getParent().resolve("state")).doesNotExist();
    }

    @Test
    void invalidReplayValuesAreRejectedBeforeGeneratingTheLocalSigningSecret() throws Exception {
        Path file = deployment("gateway", "PEER");
        Files.writeString(
                file, Files.readString(file).replace("busy-timeout: 500ms", "busy-timeout: 0ms"));
        assertThatThrownBy(() -> CoreBootstrap.open(file, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(file.getParent().resolve("keys")).doesNotExist();
        assertThat(file.getParent().resolve("state")).doesNotExist();
    }

    @Test
    void validatesAllSchemasBeforeGeneratingALocalSigningSecret() throws Exception {
        Path file = deployment("gateway", "GATEWAY");
        Files.writeString(
                file.getParent().resolve("trust/issuers.yml"), "gateway: {algorithm: HMAC}\n");
        assertThatThrownBy(() -> CoreBootstrap.open(file, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schema validation failed");
        assertThat(file.getParent().resolve("keys")).doesNotExist();
    }

    @Test
    void hmacRotationHonorsKeysPrecedenceAndRetainsApprovalOnInvalidReload() throws Exception {
        Path file = deployment("backend", "BACKEND");
        Path base = file.getParent();
        Path trustFile = base.resolve("trust/issuers.yml");
        Path original = base.resolve("trust/old.key");
        Path rotated = base.resolve("trust/new.key");
        var oldKey = HmacSha256.loadOrCreate(original);
        var newKey = HmacSha256.loadOrCreate(rotated);
        trust(file, "missing.key");
        String single = Files.readString(trustFile);
        Files.writeString(
                trustFile,
                single.replace(
                        "  key: missing.key", "  key: missing.key\n  keys: [old.key, new.key]"));
        var registries =
                CoreBootstrap.loadRegistries(base.resolve("servers.yml"), trustFile, clock);
        var approved = registries.findByIssuer("gateway").orElseThrow();
        assertThat(approved.verifiers()).containsExactly(oldKey, newKey);
        assertThat(approved.verifiers().getFirst().algorithm())
                .isEqualTo(SignatureAlgorithm.HMAC_SHA256);
        Files.write(rotated, Files.readAllBytes(original));
        assertThatThrownBy(
                        () ->
                                CoreBootstrap.reloadRegistries(
                                        registries, base.resolve("servers.yml"), trustFile, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct approved keys");
        assertThat(registries.findByIssuer("gateway")).containsSame(approved);
        Files.delete(rotated);
        assertThatThrownBy(
                        () ->
                                CoreBootstrap.reloadRegistries(
                                        registries, base.resolve("servers.yml"), trustFile, clock))
                .isInstanceOf(IOException.class);
        assertThat(registries.findByIssuer("gateway")).containsSame(approved);
        Files.writeString(
                trustFile, single.replace("key: missing.key", "key: old.key\n  enabled: false"));
        CoreBootstrap.reloadRegistries(registries, base.resolve("servers.yml"), trustFile, clock);
        assertThat(registries.findByIssuer("gateway").orElseThrow().enabled()).isFalse();
        assertThat(registries.findByIssuer("gateway").orElseThrow().verifiers())
                .isEqualTo(List.of(oldKey));
    }
}
