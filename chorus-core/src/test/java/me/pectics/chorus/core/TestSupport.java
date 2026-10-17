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
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.identity.IdentityClaim;
import me.pectics.chorus.core.identity.ProfileProperty;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;
import me.pectics.chorus.core.registry.file.FileServerNodeRegistry;
import me.pectics.chorus.core.ticket.TicketClaims;
import me.pectics.chorus.core.ticket.TicketIssuancePolicy;
import me.pectics.chorus.core.trust.IssuerTrustPolicy;
import me.pectics.chorus.core.trust.TrustedIssuer;
import me.pectics.chorus.core.trust.file.FileTrustedIssuerStore;

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

    public static Ed25519.Signer signer(KeyPair pair) {
        return new Ed25519.Signer(pair.getPrivate(), pair.getPublic());
    }

    public static IdentityClaim identity() {
        return new IdentityClaim(
                "Notch",
                AuthenticationSourceType.MOJANG,
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
                Set.of(
                        AuthenticationSourceType.MOJANG,
                        AuthenticationSourceType.OFFLINE_AUTH,
                        AuthenticationSourceType.OFFLINE_RAW),
                Set.of("mojang", "local"));
    }

    public static TicketIssuancePolicy issuancePolicy() {
        return new TicketIssuancePolicy(policy(), Set.of("backend", "next-backend"));
    }

    public static ServerNode node(String id) {
        return new ServerNode(id, id, "localhost", 25565);
    }

    public static FileServerNodeRegistry serverNodeRegistry() {
        return new FileServerNodeRegistry(
                new ServerNodeRegistrySnapshot(
                        Map.of(
                                "gateway",
                                node("gateway"),
                                "backend",
                                node("backend"),
                                "next-backend",
                                node("next-backend")),
                        NOW,
                        false));
    }

    public static FileTrustedIssuerStore issuers(KeyPair pair) {
        return new FileTrustedIssuerStore(
                List.of(
                        new TrustedIssuer(
                                "gateway",
                                List.of(new Ed25519.Verifier(pair.getPublic())),
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
