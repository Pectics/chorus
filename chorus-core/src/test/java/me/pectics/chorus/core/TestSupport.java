package me.pectics.chorus.core;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.pectics.chorus.core.crypto.SigningKey;
import me.pectics.chorus.core.crypto.VerificationKey;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.model.IdentityClaim;
import me.pectics.chorus.core.model.ProfileProperty;
import me.pectics.chorus.core.model.TicketClaims;
import me.pectics.chorus.core.policy.IssuancePolicy;
import me.pectics.chorus.core.policy.IssuerTrustPolicy;
import me.pectics.chorus.core.registry.RegistrySnapshot;
import me.pectics.chorus.core.registry.ServerNode;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.staticconfig.StaticRegistries;

public final class TestSupport {
    public static final Instant NOW = Instant.parse("2026-10-16T00:00:00Z");
    public static final UUID UUID_MOJANG = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private TestSupport() {}

    public static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static SigningKey signingKey(KeyPair pair) {
        return new SigningKey(pair.getPrivate(), pair.getPublic());
    }

    public static IdentityClaim identity() {
        return new IdentityClaim(
                "Notch",
                AuthType.MOJANG,
                "mojang",
                UUID_MOJANG.toString(),
                UUID_MOJANG,
                List.of(new ProfileProperty("textures", "skin", "signature")));
    }

    public static TicketClaims claims() {
        return new TicketClaims(
                1,
                "ticket-1",
                "gateway",
                "backend",
                NOW,
                NOW,
                NOW.plusSeconds(15),
                identity(),
                Map.of());
    }

    public static IssuerTrustPolicy policy() {
        return new IssuerTrustPolicy(
                Set.of(AuthType.MOJANG, AuthType.OFFLINE_AUTH, AuthType.OFFLINE_RAW),
                Set.of("mojang", "local"));
    }

    public static IssuancePolicy issuancePolicy() {
        return new IssuancePolicy(policy(), Set.of("backend", "next-backend"));
    }

    public static ServerNode node(String id) {
        return new ServerNode(id, id, "localhost", 25565);
    }

    public static StaticRegistries registries(KeyPair pair) {
        return new StaticRegistries(
                new RegistrySnapshot(
                        Map.of(
                                "gateway",
                                node("gateway"),
                                "backend",
                                node("backend"),
                                "next-backend",
                                node("next-backend")),
                        NOW,
                        false),
                List.of(
                        new TrustedIssuer(
                                "gateway",
                                List.of(new VerificationKey(pair.getPublic())),
                                true,
                                policy())));
    }

    public static byte[] signRaw(String header, String body, KeyPair pair) {
        try {
            var encoder = Base64.getUrlEncoder().withoutPadding();
            String input =
                    encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
                            + "."
                            + encoder.encodeToString(body.getBytes(StandardCharsets.UTF_8));
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return (input + "." + encoder.encodeToString(signer.sign()))
                    .getBytes(StandardCharsets.US_ASCII);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
