package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import me.pectics.chorus.core.api.DefaultChorusCoreEngine;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.config.CoreConfiguration;
import me.pectics.chorus.core.config.NodeRole;
import me.pectics.chorus.core.crypto.Ed25519SignatureService;
import me.pectics.chorus.core.crypto.StaticKeyRing;
import me.pectics.chorus.core.crypto.TicketCodec;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.model.AuthenticatedIdentity;
import me.pectics.chorus.core.model.IdentityClaim;
import me.pectics.chorus.core.model.ReceiverLoginContext;
import me.pectics.chorus.core.model.TicketClaims;
import me.pectics.chorus.core.model.VerifiedExternalIdentity;
import me.pectics.chorus.core.policy.IdentityPolicy;
import me.pectics.chorus.core.state.ReplayDecision;
import me.pectics.chorus.core.state.ReplayGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CoreEngineTest {
    private final java.security.KeyPair pair = TestSupport.keyPair();
    private final RecordingGuard replay = new RecordingGuard();
    private final ReceiverLoginContext login = new ReceiverLoginContext("Notch", true, null);

    public static CoreConfiguration configuration(String id, NodeRole role, IdentityPolicy policy) {
        return new CoreConfiguration(
                id,
                role,
                "chorus:ticket",
                Duration.ofSeconds(15),
                Duration.ofSeconds(15),
                Duration.ofSeconds(2),
                4096,
                2048,
                true,
                policy,
                role.sender() ? TestSupport.issuancePolicy() : null);
    }

    private DefaultChorusCoreEngine engine(String id, NodeRole role, IdentityPolicy policy) {
        var registry = TestSupport.registries(pair);
        return new DefaultChorusCoreEngine(
                configuration(id, role, policy),
                registry,
                registry,
                new StaticKeyRing(role.sender() ? TestSupport.signingKey(pair) : null, registry),
                role.receiver() ? replay : null,
                Clock.fixed(TestSupport.NOW, ZoneOffset.UTC));
    }

    private VerifyTicketRequest request(TicketClaims claims, ReceiverLoginContext context) {
        return new VerifyTicketRequest(
                new TicketCodec(4096, 2048, new Ed25519SignatureService())
                        .encode(claims, TestSupport.signingKey(pair)),
                context);
    }

    private void rejects(CompletionStage<?> stage, TicketError error) {
        assertThatThrownBy(() -> stage.toCompletableFuture().join())
                .hasCauseInstanceOf(TicketRejectedException.class)
                .satisfies(
                        e ->
                                assertThat(((TicketRejectedException) e.getCause()).code())
                                        .isEqualTo(error));
    }

    @Test
    void gatewayToPeerMapsOfflineUuidAndRetainsSourceForTheNextHop() {
        try (var sender = engine("gateway", NodeRole.GATEWAY, IdentityPolicy.ADOPT_UPSTREAM);
                var receiver =
                        engine("backend", NodeRole.PEER, IdentityPolicy.FORCE_OFFLINE_UUID)) {
            var issued =
                    sender.issueTicket(
                                    new IssueTicketRequest(
                                            "backend",
                                            new AuthenticatedIdentity(
                                                    TestSupport.identity(), "gateway"),
                                            Map.of()))
                            .toCompletableFuture()
                            .join();
            var accepted =
                    receiver.verifyAndResolve(
                                    new VerifyTicketRequest(issued.cookiePayload(), login))
                            .toCompletableFuture()
                            .join();
            assertThat(accepted.resolvedProfile().finalUuid())
                    .isEqualTo(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"));
            assertThat(accepted.retainedIdentity().claim()).isEqualTo(TestSupport.identity());
            var forwarded =
                    receiver.issueTicket(
                                    new IssueTicketRequest(
                                            "next-backend", accepted.retainedIdentity(), Map.of()))
                            .toCompletableFuture()
                            .join();
            assertThat(forwarded.targetServer().nodeId()).isEqualTo("next-backend");
            assertThat(issued.cookieNamespacedKey()).isEqualTo("chorus:ticket");
            rejects(
                    receiver.verifyAndResolve(
                            new VerifyTicketRequest(issued.cookiePayload(), login)),
                    TicketError.REPLAYED);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AuthType.class,
            names = {"MOJANG", "OFFLINE_AUTH", "OFFLINE_RAW"})
    void adoptsAuthorizedSourceUuidAndProperties(AuthType type) {
        var identity =
                new IdentityClaim(
                        "Notch",
                        type,
                        type == AuthType.MOJANG ? "mojang" : "local",
                        "account",
                        TestSupport.UUID_MOJANG,
                        TestSupport.identity().properties());
        var claims =
                claims(
                        TestSupport.NOW,
                        TestSupport.NOW,
                        TestSupport.NOW.plusSeconds(15),
                        identity,
                        "backend");
        try (var receiver = engine("backend", NodeRole.BACKEND, IdentityPolicy.ADOPT_UPSTREAM)) {
            var accepted =
                    receiver.verifyAndResolve(request(claims, login)).toCompletableFuture().join();
            assertThat(accepted.resolvedProfile().finalUuid()).isEqualTo(identity.sourceUuid());
            assertThat(accepted.resolvedProfile().properties()).isEqualTo(identity.properties());
        }
    }

    @Test
    void loginAndPolicyFailuresDoNotConsumeAndExactUsernameCanStillSucceed() {
        try (var receiver = engine("backend", NodeRole.BACKEND, IdentityPolicy.ADOPT_UPSTREAM)) {
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    TestSupport.claims(),
                                    new ReceiverLoginContext("notch", true, null))),
                    TicketError.USERNAME_MISMATCH);
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    TestSupport.claims(),
                                    new ReceiverLoginContext("Notch", false, null))),
                    TicketError.TRANSFER_REQUIRED);
            var wrongProvider =
                    new IdentityClaim(
                            "Notch",
                            AuthType.MOJANG,
                            "other",
                            "account",
                            TestSupport.UUID_MOJANG,
                            List.of());
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    claims(
                                            TestSupport.NOW,
                                            TestSupport.NOW,
                                            TestSupport.NOW.plusSeconds(15),
                                            wrongProvider,
                                            "backend"),
                                    login)),
                    TicketError.ISSUER_POLICY_DENIED);
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    claims(
                                            TestSupport.NOW,
                                            TestSupport.NOW,
                                            TestSupport.NOW.plusSeconds(15),
                                            TestSupport.identity(),
                                            "gateway"),
                                    login)),
                    TicketError.TARGET_MISMATCH);
            assertThat(replay.calls).isZero();
            assertThat(
                            receiver.verifyAndResolve(request(TestSupport.claims(), login))
                                    .toCompletableFuture()
                                    .join())
                    .isNotNull();
        }
    }

    @Test
    void checksLifetimeFutureTimesAndTheExactExpiryToleranceBoundary() {
        try (var receiver = engine("backend", NodeRole.BACKEND, IdentityPolicy.ADOPT_UPSTREAM)) {
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    claims(
                                            TestSupport.NOW,
                                            TestSupport.NOW,
                                            TestSupport.NOW.plusSeconds(16),
                                            TestSupport.identity(),
                                            "backend"),
                                    login)),
                    TicketError.INVALID_LIFETIME);
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    claims(
                                            TestSupport.NOW.plusSeconds(3),
                                            TestSupport.NOW.plusSeconds(3),
                                            TestSupport.NOW.plusSeconds(18),
                                            TestSupport.identity(),
                                            "backend"),
                                    login)),
                    TicketError.NOT_YET_VALID);
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    claims(
                                            TestSupport.NOW.minusSeconds(17),
                                            TestSupport.NOW.minusSeconds(17),
                                            TestSupport.NOW.minusSeconds(2),
                                            TestSupport.identity(),
                                            "backend"),
                                    login)),
                    TicketError.EXPIRED);
            rejects(
                    receiver.verifyAndResolve(
                            request(
                                    claims(
                                            TestSupport.NOW,
                                            TestSupport.NOW.minusSeconds(1),
                                            TestSupport.NOW.plusSeconds(15),
                                            TestSupport.identity(),
                                            "backend"),
                                    login)),
                    TicketError.INVALID_LIFETIME);
            assertThat(replay.calls).isZero();
            receiver.verifyAndResolve(
                            request(
                                    claims(
                                            TestSupport.NOW.minusSeconds(16),
                                            TestSupport.NOW.minusSeconds(16),
                                            TestSupport.NOW.minusSeconds(1),
                                            TestSupport.identity(),
                                            "backend"),
                                    login))
                    .toCompletableFuture()
                    .join();
            assertThat(replay.calls).isEqualTo(1);
        }
    }

    @Test
    void strictMatchRequiresAllIndependentIdentifiersWithoutConsumingFailures() {
        try (var receiver =
                engine("backend", NodeRole.BACKEND, IdentityPolicy.STRICT_ONLINE_MATCH)) {
            rejects(
                    receiver.verifyAndResolve(request(TestSupport.claims(), login)),
                    TicketError.EXTERNAL_IDENTITY_MISMATCH);
            var identity = TestSupport.identity();
            for (var external :
                    List.of(
                            new VerifiedExternalIdentity(
                                    "other", identity.providerSubject(), identity.sourceUuid()),
                            new VerifiedExternalIdentity("mojang", "other", identity.sourceUuid()),
                            new VerifiedExternalIdentity(
                                    "mojang", identity.providerSubject(), UUID.randomUUID()))) {
                rejects(
                        receiver.verifyAndResolve(
                                request(
                                        TestSupport.claims(),
                                        new ReceiverLoginContext("Notch", true, external))),
                        TicketError.EXTERNAL_IDENTITY_MISMATCH);
            }
            assertThat(replay.calls).isZero();
            var external =
                    new VerifiedExternalIdentity(
                            "mojang", identity.providerSubject(), identity.sourceUuid());
            var result =
                    receiver.verifyAndResolve(
                                    request(
                                            TestSupport.claims(),
                                            new ReceiverLoginContext("Notch", true, external)))
                            .toCompletableFuture()
                            .join();
            assertThat(result.resolvedProfile().finalUuid()).isEqualTo(identity.sourceUuid());
        }
    }

    @Test
    void doesNotCompleteBeforeConsumptionConfirmationAndRejectsUnknownOutcomes() {
        replay.pending = new CompletableFuture<>();
        try (var receiver = engine("backend", NodeRole.BACKEND, IdentityPolicy.ADOPT_UPSTREAM)) {
            var pending =
                    receiver.verifyAndResolve(request(TestSupport.claims(), login))
                            .toCompletableFuture();
            assertThat(pending.isDone()).isFalse();
            replay.pending.completeExceptionally(new IllegalStateException("unknown commit"));
            rejects(pending, TicketError.REPLAY_UNAVAILABLE);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ReplayDecision.class,
            names = {"REPLAYED", "UNAVAILABLE", "CAPACITY_EXCEEDED"})
    void replayRefusalsNeverReturnAProfile(ReplayDecision decision) {
        replay.pending = CompletableFuture.completedFuture(decision);
        try (var receiver = engine("backend", NodeRole.BACKEND, IdentityPolicy.ADOPT_UPSTREAM)) {
            TicketError error =
                    switch (decision) {
                        case REPLAYED -> TicketError.REPLAYED;
                        case UNAVAILABLE -> TicketError.REPLAY_UNAVAILABLE;
                        case CAPACITY_EXCEEDED -> TicketError.REPLAY_CAPACITY_EXCEEDED;
                        default -> throw new AssertionError();
                    };
            rejects(receiver.verifyAndResolve(request(TestSupport.claims(), login)), error);
        }
    }

    @Test
    void roleOperationsExtensionsAndLifecycleAreEnforced() {
        var receiver = engine("backend", NodeRole.BACKEND, IdentityPolicy.ADOPT_UPSTREAM);
        rejects(
                receiver.issueTicket(
                        new IssueTicketRequest(
                                "gateway",
                                new AuthenticatedIdentity(TestSupport.identity(), "backend"),
                                Map.of())),
                TicketError.CAPABILITY_DISABLED);
        receiver.close();
        receiver.close();
        rejects(
                receiver.verifyAndResolve(request(TestSupport.claims(), login)),
                TicketError.ENGINE_CLOSED);
        assertThat(replay.closes).isEqualTo(1);
        try (var sender = engine("gateway", NodeRole.GATEWAY, IdentityPolicy.ADOPT_UPSTREAM)) {
            rejects(
                    sender.verifyAndResolve(request(TestSupport.claims(), login)),
                    TicketError.CAPABILITY_DISABLED);
            rejects(
                    sender.issueTicket(
                            new IssueTicketRequest(
                                    "backend",
                                    new AuthenticatedIdentity(TestSupport.identity(), "gateway"),
                                    Map.of("custom:key", "value"))),
                    TicketError.MALFORMED_TICKET);
            rejects(
                    sender.issueTicket(
                            new IssueTicketRequest(
                                    "other",
                                    new AuthenticatedIdentity(TestSupport.identity(), "gateway"),
                                    Map.of())),
                    TicketError.ISSUER_POLICY_DENIED);
        }
    }

    private TicketClaims claims(
            Instant issued, Instant nbf, Instant exp, IdentityClaim identity, String target) {
        return new TicketClaims(
                1, "ticket", "gateway", target, issued, nbf, exp, identity, Map.of());
    }

    private static final class RecordingGuard implements ReplayGuard {
        private int calls;
        private int closes;
        private CompletableFuture<ReplayDecision> pending;
        private final Set<String> consumed = new HashSet<>();

        @Override
        public CompletionStage<ReplayDecision> consume(
                String issuer, String jti, String target, Instant expiry) {
            calls++;
            return pending != null
                    ? pending
                    : CompletableFuture.completedFuture(
                            consumed.add(issuer + "/" + jti)
                                    ? ReplayDecision.ACCEPTED
                                    : ReplayDecision.REPLAYED);
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
