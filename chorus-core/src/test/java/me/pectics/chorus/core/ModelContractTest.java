package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.identity.BuiltinIdentityResolutionPolicy;
import me.pectics.chorus.core.identity.IdentityClaim;
import me.pectics.chorus.core.identity.ProfileProperty;
import me.pectics.chorus.core.identity.ReceiverLoginContext;
import me.pectics.chorus.core.identity.ResolvedPlayerProfile;
import me.pectics.chorus.core.identity.VerifiedExternalIdentity;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.node.ServerNodeConfiguration;
import me.pectics.chorus.core.node.ServerNodeRole;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import me.pectics.chorus.core.ticket.TicketClaims;
import me.pectics.chorus.core.ticket.TicketCompression;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuanceConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuancePolicy;
import me.pectics.chorus.core.ticket.TicketVerificationConfiguration;
import me.pectics.chorus.core.trust.IssuerTrustPolicy;
import org.junit.jupiter.api.Test;

class ModelContractTest {
    @Test
    void mappingPreservesTheSourceIdentityAndCopiesProperties() {
        var properties = new ArrayList<>(List.of(new ProfileProperty("textures", "skin", null)));
        var identity =
                new IdentityClaim(
                        "Notch",
                        AuthenticationSourceType.MOJANG,
                        "mojang",
                        "account",
                        UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                        properties);
        properties.clear();
        var adopted = BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM.resolve(identity);
        var offline = BuiltinIdentityResolutionPolicy.FORCE_OFFLINE_UUID.resolve(identity);
        assertThat(adopted.finalUuid()).isEqualTo(identity.sourceUuid());
        assertThat(offline.finalUuid())
                .isEqualTo(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"));
        assertThat(offline.originalIdentity()).isSameAs(identity);
        assertThat(identity.properties()).hasSize(1);
    }

    @Test
    void payloadIsCopiedOnInputAndOutputAndMissingCookieIsRepresentable() {
        byte[] bytes = {1, 2};
        var context = new ReceiverLoginContext("Notch", true, null);
        var request = new VerifyTicketRequest(bytes, context);
        bytes[0] = 3;
        request.cookiePayload()[1] = 4;
        assertThat(request.cookiePayload()).containsExactly(1, 2);
        assertThat(new VerifyTicketRequest(null, context).cookiePayload()).isNull();
    }

    @Test
    void phaseOneRejectsArbitraryPropertiesAndExtensions() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProfileProperty("admin", "true", null));
        var identity =
                new IdentityClaim(
                        "Notch",
                        AuthenticationSourceType.OFFLINE_RAW,
                        "local",
                        "Notch",
                        UUID.randomUUID(),
                        List.of());
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new TicketClaims(
                                        1,
                                        "jti",
                                        "gateway",
                                        "backend",
                                        Instant.EPOCH,
                                        Instant.EPOCH,
                                        Instant.EPOCH.plusSeconds(15),
                                        identity,
                                        Map.of("custom:value", "x")));
    }

    @Test
    void identityClaimsEnforceNameProviderSubjectAndPropertyBounds() {
        assertThat(identity("A", "mojang", "subject").username()).isEqualTo("A");
        assertThat(identity("A".repeat(16), "a".repeat(128), "x".repeat(256)).username())
                .hasSize(16);
        for (String username : List.of("", "A".repeat(17), "bad-name", "bad name", "Ünicode")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> identity(username, "mojang", "subject"));
        }
        for (String provider : List.of("", "Mojang", ".mojang", "a".repeat(129))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> identity("Notch", provider, "subject"));
        }
        for (String subject : List.of("", " ", "x".repeat(257))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> identity("Notch", "mojang", subject));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                identity(
                                        "Notch",
                                        "mojang",
                                        "subject",
                                        List.of(
                                                new ProfileProperty("textures", "a", null),
                                                new ProfileProperty("textures", "b", null))));
        for (String name : List.of("admin", "Textures", "")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ProfileProperty(name, "skin", null));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProfileProperty("textures", "", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ProfileProperty("textures", "skin", ""));
        assertThat(new ProfileProperty("textures", "skin", null).signature()).isNull();
    }

    @Test
    void serverNodesAndLocalNodeConfigurationEnforceRoutingIdentifiers() {
        String maximumId = "a".repeat(128);
        assertThat(new ServerNode(maximumId, "display", "localhost", 1).port()).isEqualTo(1);
        assertThat(new ServerNode("backend", "display", "::1", 65535).port()).isEqualTo(65535);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServerNode("bad id", "display", "localhost", 25565));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServerNode("a".repeat(129), "display", "localhost", 25565));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServerNode("backend", " ", "localhost", 25565));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServerNode("backend", "display", "local host", 25565));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServerNode("backend", "display", "", 25565));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServerNode("backend", "display", "localhost", 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ServerNode("backend", "display", "localhost", 65536));

        assertThat(new ServerNodeConfiguration(maximumId, ServerNodeRole.PEER).role())
                .isEqualTo(ServerNodeRole.PEER);
        assertThat(new ServerNodeConfiguration("backend", "Backend", ServerNodeRole.BACKEND))
                .isEqualTo(
                        new ServerNodeConfiguration("backend", "Backend", ServerNodeRole.BACKEND));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ServerNodeConfiguration("backend", " ", ServerNodeRole.BACKEND));
        assertThatNullPointerException()
                .isThrownBy(() -> new ServerNodeConfiguration("backend", null));
    }

    @Test
    void registrySnapshotsAreImmutableAndKeyedByNodeIdentity() {
        var nodes = new HashMap<>(Map.of("backend", TestSupport.node("backend")));
        var snapshot = new ServerNodeRegistrySnapshot(nodes, TestSupport.NOW, false);
        nodes.clear();
        assertThat(snapshot.serverNodes()).containsOnlyKeys("backend");
        assertThatThrownBy(() -> snapshot.serverNodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ServerNodeRegistrySnapshot(
                                        Map.of("other", TestSupport.node("backend")),
                                        TestSupport.NOW,
                                        false));
        assertThatNullPointerException()
                .isThrownBy(() -> new ServerNodeRegistrySnapshot(Map.of(), null, false));
    }

    @Test
    void ticketClaimsEnforceWireIdentifiersAndRejectExtensions() {
        String maximumId = "a".repeat(128);
        var valid =
                new TicketClaims(
                        1,
                        maximumId,
                        maximumId,
                        maximumId,
                        TestSupport.NOW,
                        TestSupport.NOW,
                        TestSupport.NOW.plusSeconds(15),
                        TestSupport.identity(),
                        Map.of());
        assertThat(valid.jti()).hasSize(128);
        for (String blank : List.of("", " ")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> claims(blank, "gateway", "backend"));
            assertThatIllegalArgumentException().isThrownBy(() -> claims("jti", blank, "backend"));
            assertThatIllegalArgumentException().isThrownBy(() -> claims("jti", "gateway", blank));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> claims("a".repeat(129), "gateway", "backend"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> claims("jti", "a".repeat(129), "backend"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> claims("jti", "gateway", "a".repeat(129)));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new TicketClaims(
                                        1,
                                        null,
                                        "gateway",
                                        "backend",
                                        TestSupport.NOW,
                                        TestSupport.NOW,
                                        TestSupport.NOW.plusSeconds(15),
                                        TestSupport.identity(),
                                        Map.of()));
    }

    @Test
    void ticketConfigurationsRejectOutOfRangeOperationalValues() {
        assertThat(new TicketConfiguration("chorus:ticket", 1, 1).compression())
                .isEqualTo(TicketCompression.ZSTD);
        assertThat(new TicketConfiguration("a:b/c.d_e-f", 5120, 5120).maxPayloadBytes())
                .isEqualTo(5120);
        for (String cookie :
                List.of("", "chorus", "Chorus:ticket", "chorus:", "chorus:Ticket", " ")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TicketConfiguration(cookie, 4096, 2048));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TicketConfiguration("chorus:ticket", 0, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TicketConfiguration("chorus:ticket", 5121, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TicketConfiguration("chorus:ticket", 4096, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TicketConfiguration("chorus:ticket", 4096, 4097));
        assertThatNullPointerException()
                .isThrownBy(() -> new TicketConfiguration("chorus:ticket", 4096, 2048, null));

        var policy = TestSupport.issuancePolicy();
        assertThat(new TicketIssuanceConfiguration(Duration.ofSeconds(1), policy).ttl())
                .isEqualTo(Duration.ofSeconds(1));
        for (Duration ttl :
                List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TicketIssuanceConfiguration(ttl, policy));
        }
        assertThatNullPointerException()
                .isThrownBy(() -> new TicketIssuanceConfiguration(null, policy));

        assertThat(
                        new TicketVerificationConfiguration(
                                        Duration.ofSeconds(15),
                                        Duration.ZERO,
                                        true,
                                        BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM)
                                .clockSkew())
                .isZero();
        for (Duration invalid :
                List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new TicketVerificationConfiguration(
                                            invalid,
                                            Duration.ZERO,
                                            true,
                                            BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM));
        }
        for (Duration invalid :
                List.of(Duration.ofSeconds(-1), Duration.ofMillis(500), Duration.ofNanos(1))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    new TicketVerificationConfiguration(
                                            Duration.ofSeconds(15),
                                            invalid,
                                            true,
                                            BuiltinIdentityResolutionPolicy.ADOPT_UPSTREAM));
        }
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new TicketVerificationConfiguration(
                                        Duration.ofSeconds(15), Duration.ZERO, true, null));
    }

    @Test
    void replayConfigurationRequiresLocalSettingsOnlyForTheLocalBackend() {
        var path = Path.of("state/replay.db");
        assertThat(new ReplayGuardConfiguration.Local(path, 1, Duration.ofMillis(1)).maxEntries())
                .isEqualTo(1);
        assertThat(
                        new ReplayGuardConfiguration.Local(
                                        path,
                                        Integer.MAX_VALUE,
                                        Duration.ofMillis(Integer.MAX_VALUE))
                                .busyTimeout())
                .isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new ReplayGuardConfiguration.Local(path, 0, Duration.ofMillis(1)));
        for (Duration timeout :
                List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1))) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReplayGuardConfiguration.Local(path, 1, timeout));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration.Local(
                                        path, 1, Duration.ofMillis((long) Integer.MAX_VALUE + 1)));
        assertThatNullPointerException()
                .isThrownBy(
                        () -> new ReplayGuardConfiguration.Local(null, 1, Duration.ofMillis(1)));
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ReplayGuardConfiguration(
                                        ReplayGuardConfiguration.Backend.LOCAL, null));
    }

    @Test
    void trustAndIssuancePoliciesRequireExplicitNonEmptyPermissions() {
        var policy = TestSupport.policy();
        assertThat(policy.permits(TestSupport.identity())).isTrue();
        assertThat(
                        policy.permits(
                                identity(
                                        "Notch",
                                        "local",
                                        "subject",
                                        List.of(),
                                        AuthenticationSourceType.OFFLINE_AUTH)))
                .isTrue();
        assertThat(
                        policy.permits(
                                identity(
                                        "Notch",
                                        "other",
                                        "subject",
                                        List.of(),
                                        AuthenticationSourceType.MOJANG)))
                .isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IssuerTrustPolicy(Set.of(), Set.of("mojang")));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new IssuerTrustPolicy(
                                        Set.of(AuthenticationSourceType.MOJANG), Set.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new IssuerTrustPolicy(
                                        Set.of(AuthenticationSourceType.MOJANG), Set.of("Mojang")));

        var issuance = TestSupport.issuancePolicy();
        assertThat(issuance.permits(TestSupport.identity(), "backend")).isTrue();
        assertThat(issuance.permits(TestSupport.identity(), "gateway")).isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TicketIssuancePolicy(policy, Set.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TicketIssuancePolicy(policy, Set.of("bad target")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TicketIssuancePolicy(policy, Set.of(".backend")));
    }

    @Test
    void roleCapabilitiesFollowTheDocumentedMatrix() {
        assertThat(ServerNodeRole.GATEWAY.sender()).isTrue();
        assertThat(ServerNodeRole.GATEWAY.receiver()).isFalse();
        assertThat(ServerNodeRole.BACKEND.sender()).isFalse();
        assertThat(ServerNodeRole.BACKEND.receiver()).isTrue();
        assertThat(ServerNodeRole.PEER.sender()).isTrue();
        assertThat(ServerNodeRole.PEER.receiver()).isTrue();
    }

    @Test
    void loginContextsAndResolvedProfilesRejectMissingInputs() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReceiverLoginContext(null, true, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new VerifiedExternalIdentity(null, "subject", UUID.randomUUID()));
        assertThatNullPointerException()
                .isThrownBy(() -> new VerifiedExternalIdentity("mojang", null, UUID.randomUUID()));
        assertThatNullPointerException()
                .isThrownBy(() -> new VerifiedExternalIdentity("mojang", "subject", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VerifiedExternalIdentity(" ", "subject", UUID.randomUUID()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new VerifiedExternalIdentity("mojang", " ", UUID.randomUUID()));

        assertThatNullPointerException()
                .isThrownBy(() -> new AuthenticatedIdentity(null, "gateway"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AuthenticatedIdentity(TestSupport.identity(), " "));

        var properties = new ArrayList<>(List.of(new ProfileProperty("textures", "skin", null)));
        var profile =
                new ResolvedPlayerProfile(
                        TestSupport.UUID_MOJANG, "Notch", properties, TestSupport.identity());
        properties.clear();
        assertThat(profile.properties()).hasSize(1);
        assertThatThrownBy(() -> profile.properties().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ResolvedPlayerProfile(
                                        null, "Notch", List.of(), TestSupport.identity()));
    }

    private static IdentityClaim identity(String username, String provider, String subject) {
        return identity(
                username,
                provider,
                subject,
                List.of(new ProfileProperty("textures", "skin", null)),
                AuthenticationSourceType.MOJANG);
    }

    private static IdentityClaim identity(
            String username, String provider, String subject, List<ProfileProperty> properties) {
        return identity(username, provider, subject, properties, AuthenticationSourceType.MOJANG);
    }

    private static IdentityClaim identity(
            String username,
            String provider,
            String subject,
            List<ProfileProperty> properties,
            AuthenticationSourceType type) {
        return new IdentityClaim(
                username, type, provider, subject, TestSupport.UUID_MOJANG, properties);
    }

    private static TicketClaims claims(String jti, String issuer, String target) {
        return new TicketClaims(
                1,
                jti,
                issuer,
                target,
                TestSupport.NOW,
                TestSupport.NOW,
                TestSupport.NOW.plusSeconds(15),
                TestSupport.identity(),
                Map.of());
    }
}
