/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/engine/DefaultChorusCoreEngine.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/api/TicketError.java
 * sync: Keep Registry failures distinct from missing nodes and independent local issuer trust.
 * @endcontext
 */
package me.pectics.chorus.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
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
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.identity.BuiltinIdentityResolutionPolicy;
import me.pectics.chorus.core.identity.IdentityClaim;
import me.pectics.chorus.core.identity.ReceiverLoginContext;
import me.pectics.chorus.core.identity.VerifiedExternalIdentity;
import me.pectics.chorus.core.node.ServerNodeConfiguration;
import me.pectics.chorus.core.node.ServerNodeRole;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;
import me.pectics.chorus.core.replay.ReplayDecision;
import me.pectics.chorus.core.replay.ReplayGuard;
import me.pectics.chorus.core.ticket.TicketClaims;
import me.pectics.chorus.core.ticket.TicketCodec;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuanceConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuancePolicy;
import me.pectics.chorus.core.ticket.TicketVerificationConfiguration;
import me.pectics.chorus.core.trust.IssuerTrustPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DefaultChorusCoreEngineTest {
    private final java.security.KeyPair pair = TestSupport.keyPair();
    private final RecordingGuard replay = new RecordingGuard();
    private final ReceiverLoginContext login = new ReceiverLoginContext("Notch", true, null);

    private DefaultChorusCoreEngine engine(
            String id, ServerNodeRole role, BuiltinIdentityResolutionPolicy policy) {
        return engine(
                id,
                role,
                policy,
                TestSupport.serverNodeRegistry(),
                TestSupport.issuers(pair),
                role.sender()
                        ? new TicketIssuanceConfiguration(
                                Duration.ofSeconds(15), TestSupport.issuancePolicy())
                        : null,
                new TicketVerificationConfiguration(
                        Duration.ofSeconds(15), Duration.ofSeconds(2), true, policy),
                Clock.fixed(TestSupport.NOW, ZoneOffset.UTC));
    }

    private DefaultChorusCoreEngine engine(
            String id,
            ServerNodeRole role,
            BuiltinIdentityResolutionPolicy policy,
            ServerNodeRegistry serverNodeRegistry) {
        return engine(
                id,
                role,
                policy,
                serverNodeRegistry,
                TestSupport.issuers(pair),
                role.sender()
                        ? new TicketIssuanceConfiguration(
                                Duration.ofSeconds(15), TestSupport.issuancePolicy())
                        : null,
                new TicketVerificationConfiguration(
                        Duration.ofSeconds(15), Duration.ofSeconds(2), true, policy),
                Clock.fixed(TestSupport.NOW, ZoneOffset.UTC));
    }

    private DefaultChorusCoreEngine engine(
            String id,
            ServerNodeRole role,
            BuiltinIdentityResolutionPolicy policy,
            ServerNodeRegistry serverNodeRegistry,
            me.pectics.chorus.core.trust.TrustedIssuerStore issuers,
            TicketIssuanceConfiguration issuance,
            TicketVerificationConfiguration verification,
            Clock clock) {
        return new DefaultChorusCoreEngine(
                new ServerNodeConfiguration(id, role),
                new TicketConfiguration("chorus:ticket", 4096, 2048),
                issuance,
                verification,
                serverNodeRegistry,
                issuers,
                role.sender() ? TestSupport.signer(pair) : null,
                role.receiver() ? replay : null,
                clock);
    }

    @Test
    void independentSectionsStillEnforceSenderRequirementsBeforeTakingReplayOwnership() {
        var registry = TestSupport.serverNodeRegistry();
        var issuers = TestSupport.issuers(pair);
        var node = new ServerNodeConfiguration("gateway", ServerNodeRole.PEER);
        var ticket = new TicketConfiguration("chorus:ticket", 4096, 2048);
        var verification =
                new TicketVerificationConfiguration(
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(2),
                        true,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM);
        assertThatThrownBy(
                        () ->
                                new DefaultChorusCoreEngine(
                                        node,
                                        ticket,
                                        null,
                                        verification,
                                        registry,
                                        issuers,
                                        TestSupport.signer(pair),
                                        replay,
                                        Clock.systemUTC()))
                .isInstanceOf(NullPointerException.class);
        var issuance =
                new TicketIssuanceConfiguration(
                        Duration.ofSeconds(16), TestSupport.issuancePolicy());
        assertThatThrownBy(
                        () ->
                                new DefaultChorusCoreEngine(
                                        node,
                                        ticket,
                                        issuance,
                                        verification,
                                        registry,
                                        issuers,
                                        TestSupport.signer(pair),
                                        replay,
                                        Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(replay.closes).isZero();
    }

    private VerifyTicketRequest request(TicketClaims claims, ReceiverLoginContext context) {
        return new VerifyTicketRequest(
                new TicketCodec(4096, 2048).encode(claims, TestSupport.signer(pair)), context);
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
    void unavailableRegistryRejectsIssuanceAtTheRegistryBoundary() {
        try (var sender =
                engine(
                        "gateway",
                        ServerNodeRole.GATEWAY,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM,
                        unavailableRegistry())) {
            rejects(
                    sender.issueTicket(
                            new IssueTicketRequest(
                                    "backend",
                                    new AuthenticatedIdentity(TestSupport.identity(), "gateway"),
                                    Map.of())),
                    TicketError.REGISTRY_UNAVAILABLE);
        }
    }

    @Test
    void availableRegistryWithoutTheTargetRejectsAsUnknownTarget() {
        var snapshot = new ServerNodeRegistrySnapshot(Map.of(), TestSupport.NOW, false);
        try (var sender =
                engine(
                        "gateway",
                        ServerNodeRole.GATEWAY,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM,
                        () -> snapshot)) {
            rejects(
                    sender.issueTicket(
                            new IssueTicketRequest(
                                    "backend",
                                    new AuthenticatedIdentity(TestSupport.identity(), "gateway"),
                                    Map.of())),
                    TicketError.UNKNOWN_TARGET);
        }
    }

    @Test
    void unavailableRegistryDoesNotPreventVerificationUsingLocalIssuerTrust() {
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM,
                        unavailableRegistry())) {
            var accepted =
                    receiver.verifyAndResolve(request(TestSupport.claims(), login))
                            .toCompletableFuture()
                            .join();
            assertThat(accepted.resolvedProfile().finalUuid()).isEqualTo(TestSupport.UUID_MOJANG);
            assertThat(replay.calls).isEqualTo(1);
        }
    }

    private static ServerNodeRegistry unavailableRegistry() {
        return () -> {
            throw new IllegalStateException("Registry access failed");
        };
    }

    @Test
    void gatewayToPeerMapsOfflineUuidAndRetainsSourceForTheNextHop() {
        try (var sender =
                        engine(
                                "gateway",
                                ServerNodeRole.GATEWAY,
                                BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM);
                var receiver =
                        engine(
                                "backend",
                                ServerNodeRole.PEER,
                                BuiltinIdentityResolutionPolicy.FORCE_OFFLINE_UUID)) {
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
            value = AuthenticationSourceType.class,
            names = {"MOJANG", "OFFLINE_AUTH", "OFFLINE_RAW"})
    void adoptsAuthorizedSourceUuidAndProperties(AuthenticationSourceType type) {
        var identity =
                new IdentityClaim(
                        "Notch",
                        type,
                        type == AuthenticationSourceType.MOJANG ? "mojang" : "local",
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
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)) {
            var accepted =
                    receiver.verifyAndResolve(request(claims, login)).toCompletableFuture().join();
            assertThat(accepted.resolvedProfile().finalUuid()).isEqualTo(identity.sourceUuid());
            assertThat(accepted.resolvedProfile().properties()).isEqualTo(identity.properties());
        }
    }

    @Test
    void loginAndPolicyFailuresDoNotConsumeAndExactUsernameCanStillSucceed() {
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)) {
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
                            AuthenticationSourceType.MOJANG,
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
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)) {
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
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.STRICT_ONLINE_MATCH)) {
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
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)) {
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
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)) {
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
        var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM);
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
        try (var sender =
                engine(
                        "gateway",
                        ServerNodeRole.GATEWAY,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)) {
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

    @Test
    void reportsTheLocalValidationNodeAndConsumesTheExactTicketIdentity() {
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)) {
            var accepted =
                    receiver.verifyAndResolve(request(TestSupport.claims(), login))
                            .toCompletableFuture()
                            .join();
            assertThat(accepted.retainedIdentity())
                    .isEqualTo(new AuthenticatedIdentity(TestSupport.identity(), "backend"));
            assertThat(accepted.jti()).isEqualTo("ticket-1");
            assertThat(replay.issuer).isEqualTo("gateway");
            assertThat(replay.jti).isEqualTo("ticket-1");
            assertThat(replay.target).isEqualTo("backend");
            assertThat(replay.expiry).isEqualTo(TestSupport.NOW.plusSeconds(15));
        }
    }

    @Test
    void issuanceTruncatesToWholeSecondsAndProducesUniqueIdentifiers() {
        var clock = Clock.fixed(TestSupport.NOW.plusNanos(999_999_999), ZoneOffset.UTC);
        var verification =
                new TicketVerificationConfiguration(
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(2),
                        true,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM);
        try (var sender =
                engine(
                        "gateway",
                        ServerNodeRole.GATEWAY,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM,
                        TestSupport.serverNodeRegistry(),
                        TestSupport.issuers(pair),
                        new TicketIssuanceConfiguration(
                                Duration.ofSeconds(15), TestSupport.issuancePolicy()),
                        verification,
                        clock)) {
            var first =
                    sender.issueTicket(
                                    new IssueTicketRequest(
                                            "backend",
                                            new AuthenticatedIdentity(
                                                    TestSupport.identity(), "gateway"),
                                            Map.of()))
                            .toCompletableFuture()
                            .join();
            var second =
                    sender.issueTicket(
                                    new IssueTicketRequest(
                                            "backend",
                                            new AuthenticatedIdentity(
                                                    TestSupport.identity(), "gateway"),
                                            Map.of()))
                            .toCompletableFuture()
                            .join();
            var issued =
                    new TicketCodec(4096, 2048)
                            .decode(first.cookiePayload(), TestSupport.issuers(pair))
                            .claims();
            assertThat(issued.issuedAt()).isEqualTo(TestSupport.NOW);
            assertThat(issued.notBefore()).isEqualTo(TestSupport.NOW);
            assertThat(issued.expiresAt()).isEqualTo(TestSupport.NOW.plusSeconds(15));
            assertThat(first.expiresAt()).isEqualTo(TestSupport.NOW.plusSeconds(15));
            assertThat(first.jti()).isNotEqualTo(second.jti());
            assertThat(UUID.fromString(first.jti()).toString()).isEqualTo(first.jti());
            assertThat(first.targetServer()).isEqualTo(TestSupport.node("backend"));
            assertThat(first.cookieNamespacedKey()).isEqualTo("chorus:ticket");
        }
    }

    @Test
    void transferIntentEnforcementCanBeDisabledByConfiguration() {
        var verification =
                new TicketVerificationConfiguration(
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(2),
                        false,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM);
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM,
                        TestSupport.serverNodeRegistry(),
                        TestSupport.issuers(pair),
                        null,
                        verification,
                        Clock.fixed(TestSupport.NOW, ZoneOffset.UTC))) {
            var accepted =
                    receiver.verifyAndResolve(
                                    request(
                                            TestSupport.claims(),
                                            new ReceiverLoginContext("Notch", false, null)))
                            .toCompletableFuture()
                            .join();
            assertThat(accepted.resolvedProfile().finalUuid()).isEqualTo(TestSupport.UUID_MOJANG);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AuthenticationSourceType.class,
            names = {"YGGDRASIL", "FLOODGATE"})
    void reservedIdentitySchemesAreRejectedEvenWhenPoliciesPermitThem(
            AuthenticationSourceType type) {
        var identity =
                new IdentityClaim(
                        "Notch", type, "mojang", "account", TestSupport.UUID_MOJANG, List.of());
        var permitted = new IssuerTrustPolicy(Set.of(type), Set.of("mojang"));
        var issuers =
                new me.pectics.chorus.core.trust.file.FileTrustedIssuerStore(
                        List.of(
                                new me.pectics.chorus.core.trust.TrustedIssuer(
                                        "gateway",
                                        List.of(
                                                new me.pectics.chorus.core.crypto.Ed25519.Verifier(
                                                        pair.getPublic())),
                                        true,
                                        permitted)));
        try (var sender =
                engine(
                        "gateway",
                        ServerNodeRole.GATEWAY,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM,
                        TestSupport.serverNodeRegistry(),
                        issuers,
                        new TicketIssuanceConfiguration(
                                Duration.ofSeconds(15),
                                new TicketIssuancePolicy(permitted, Set.of("backend"))),
                        new TicketVerificationConfiguration(
                                Duration.ofSeconds(15),
                                Duration.ofSeconds(2),
                                true,
                                BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM),
                        Clock.fixed(TestSupport.NOW, ZoneOffset.UTC))) {
            rejects(
                    sender.issueTicket(
                            new IssueTicketRequest(
                                    "backend",
                                    new AuthenticatedIdentity(identity, "gateway"),
                                    Map.of())),
                    TicketError.ISSUER_POLICY_DENIED);
        }
        var claims =
                claims(
                        TestSupport.NOW,
                        TestSupport.NOW,
                        TestSupport.NOW.plusSeconds(15),
                        identity,
                        "backend");
        try (var receiver =
                engine(
                        "backend",
                        ServerNodeRole.BACKEND,
                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM,
                        TestSupport.serverNodeRegistry(),
                        issuers,
                        null,
                        new TicketVerificationConfiguration(
                                Duration.ofSeconds(15),
                                Duration.ofSeconds(2),
                                true,
                                BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM),
                        Clock.fixed(TestSupport.NOW, ZoneOffset.UTC))) {
            rejects(
                    receiver.verifyAndResolve(request(claims, login)),
                    TicketError.ISSUER_POLICY_DENIED);
            assertThat(replay.calls).isZero();
        }
    }

    @Test
    void verificationRequestRequiresALoginContextBeforeTheEngineIsInvoked() {
        var encoded =
                new TicketCodec(4096, 2048).encode(TestSupport.claims(), TestSupport.signer(pair));
        assertThatNullPointerException().isThrownBy(() -> new VerifyTicketRequest(encoded, null));
        assertThat(replay.calls).isZero();
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
        private String issuer;
        private String jti;
        private String target;
        private Instant expiry;

        @Override
        public CompletionStage<ReplayDecision> consume(
                String issuer, String jti, String target, Instant expiry) {
            calls++;
            this.issuer = issuer;
            this.jti = jti;
            this.target = target;
            this.expiry = expiry;
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
