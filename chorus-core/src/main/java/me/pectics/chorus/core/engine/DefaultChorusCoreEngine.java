/*
 * @context
 * refs: ARCHITECTURE.md, section 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuard.java
 * sync: Complete policy and profile checks before durable consumption; never compensate a consumed ticket.
 * @endcontext
 */
package me.pectics.chorus.core.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.api.IssueTicketRequest;
import me.pectics.chorus.core.api.IssueTicketResult;
import me.pectics.chorus.core.api.TicketError;
import me.pectics.chorus.core.api.TicketRejectedException;
import me.pectics.chorus.core.api.VerifyTicketRequest;
import me.pectics.chorus.core.api.VerifyTicketResult;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.identity.AuthenticatedIdentity;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.identity.BuiltinIdentityResolutionPolicy;
import me.pectics.chorus.core.identity.ReceiverLoginContext;
import me.pectics.chorus.core.identity.ResolvedPlayerProfile;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.node.ServerNodeConfiguration;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.replay.ReplayGuard;
import me.pectics.chorus.core.ticket.TicketClaims;
import me.pectics.chorus.core.ticket.TicketCodec;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuanceConfiguration;
import me.pectics.chorus.core.ticket.TicketTiming;
import me.pectics.chorus.core.ticket.TicketVerificationConfiguration;
import me.pectics.chorus.core.trust.TrustedIssuerStore;

/**
 * Phase-1 engine. Takes ownership of ReplayGuard; other injected collaborators remain caller-owned.
 * Sender roles require a signer and may pass a replay backend to pre-register; receiver-only nodes
 * may pass null issuance and signer. Signing material is fixed for this engine's lifetime; trusted
 * verification snapshots can still be replaced independently.
 */
public final class DefaultChorusCoreEngine implements ChorusCoreEngine {
    private final ServerNodeConfiguration node;
    private final TicketConfiguration ticket;
    private final TicketIssuanceConfiguration issuance;
    private final TicketVerificationConfiguration verification;
    private final ServerNodeRegistry serverNodeRegistry;
    private final TrustedIssuerStore issuers;
    private final TicketSigner signer;
    private final ReplayGuard replay;
    private final Clock clock;
    private final TicketCodec codec;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Accepts independent runtime sections and already assembled collaborators, without file I/O.
     * Sender roles require issuance settings and a signer; their TTL must not exceed the ticket's
     * maximum lifetime. Receiver roles require verification rules, trusted issuers, and a replay
     * guard. Missing required arguments throw NullPointerException; an excessive TTL throws
     * IllegalArgumentException. Issuance may be null for receiver-only nodes. Replay ownership
     * transfers only after construction succeeds.
     */
    public DefaultChorusCoreEngine(
            ServerNodeConfiguration node,
            TicketConfiguration ticket,
            TicketIssuanceConfiguration issuance,
            TicketVerificationConfiguration verification,
            ServerNodeRegistry serverNodeRegistry,
            TrustedIssuerStore issuers,
            TicketSigner signer,
            ReplayGuard replay,
            Clock clock) {
        this.node = Objects.requireNonNull(node, "node");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.issuance = issuance;
        this.verification = verification;
        this.serverNodeRegistry = Objects.requireNonNull(serverNodeRegistry, "serverNodeRegistry");
        this.issuers = issuers;
        this.signer = signer;
        this.replay = replay;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec =
                new TicketCodec(
                        ticket.maxPayloadBytes(), ticket.maxPropertyBytes(), ticket.compression());
        if (node.role().sender()) {
            Objects.requireNonNull(issuance, "Sender requires issuance settings");
            if (issuance.ttl().compareTo(ticket.maxLifetime()) > 0) {
                throw new IllegalArgumentException("Issuance TTL exceeds maximum ticket lifetime");
            }
            Objects.requireNonNull(signer, "Sender requires a ticket signer");
        }
        if (node.role().receiver()) {
            Objects.requireNonNull(verification, "Receiver requires verification rules");
            Objects.requireNonNull(issuers, "Receiver requires trusted issuers");
            Objects.requireNonNull(replay, "Receiver requires a replay guard");
        }
    }

    @Override
    public ServerNodeRegistry getServerNodeRegistry() {
        return serverNodeRegistry;
    }

    @Override
    public String getCookieKey() {
        return ticket.cookieKey();
    }

    @Override
    public CompletionStage<IssueTicketResult> issueTicket(IssueTicketRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            requireOpen();
            if (!node.role().sender()) {
                throw reject(TicketError.CAPABILITY_DISABLED);
            }
            var identity = request.identity().claim();
            if (!supported(identity.authType())
                    || !issuance.policy().permits(identity, request.targetNodeId())) {
                throw reject(TicketError.ISSUER_POLICY_DENIED);
            }
            if (!request.extensions().isEmpty()) {
                throw reject(TicketError.MALFORMED_TICKET);
            }
            ServerNode target;
            try {
                target =
                        serverNodeRegistry
                                .find(request.targetNodeId())
                                .orElseThrow(() -> reject(TicketError.UNKNOWN_TARGET));
            } catch (IllegalStateException unavailable) {
                throw reject(TicketError.REGISTRY_UNAVAILABLE);
            }
            Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            var claims =
                    new TicketClaims(
                            1,
                            UUID.randomUUID().toString(),
                            node.id(),
                            request.targetNodeId(),
                            issuedAt,
                            issuedAt,
                            issuedAt.plus(issuance.ttl()),
                            identity,
                            request.extensions());
            var result =
                    new IssueTicketResult(
                            target,
                            ticket.cookieKey(),
                            codec.encode(claims, signer),
                            claims.jti(),
                            claims.expiresAt());
            if (replay == null) {
                return CompletableFuture.completedFuture(result);
            }
            return replay.register(
                            node.id(), claims.jti(), claims.targetNodeId(), claims.expiresAt())
                    .handle(
                            (ignored, failure) -> {
                                if (failure != null) {
                                    throw reject(TicketError.REPLAY_REGISTRATION_FAILED);
                                }
                                return result;
                            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<VerifyTicketResult> verifyAndResolve(VerifyTicketRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            requireOpen();
            if (!node.role().receiver()) {
                throw reject(TicketError.CAPABILITY_DISABLED);
            }
            var decoded = codec.decode(request.cookiePayload(), issuers);
            TicketClaims claims = decoded.claims();
            if (!node.id().equals(claims.targetNodeId())) {
                throw reject(TicketError.TARGET_MISMATCH);
            }
            validateTimes(claims);
            ReceiverLoginContext login = request.loginContext();
            if (verification.enforceTransferIntent() && !login.transferred()) {
                throw reject(TicketError.TRANSFER_REQUIRED);
            }
            if (!claims.identity().username().equals(login.loginUsername())) {
                throw reject(TicketError.USERNAME_MISMATCH);
            }
            if (!supported(claims.identity().authType())
                    || !decoded.issuer().policy().permits(claims.identity())) {
                throw reject(TicketError.ISSUER_POLICY_DENIED);
            }
            if (verification.identityResolutionPolicy()
                    == BuiltinIdentityResolutionPolicy.STRICT_ONLINE_MATCH) {
                var verified = login.independentlyVerified();
                var identity = claims.identity();
                if (verified == null
                        || identity.authType() != AuthenticationSourceType.MOJANG
                        || !identity.authProvider().equals(verified.authProvider())
                        || !identity.providerSubject().equals(verified.providerSubject())
                        || !identity.sourceUuid().equals(verified.verifiedUuid())) {
                    throw reject(TicketError.EXTERNAL_IDENTITY_MISMATCH);
                }
            }
            ResolvedPlayerProfile profile;
            try {
                profile = verification.identityResolutionPolicy().resolve(claims.identity());
            } catch (RuntimeException resolutionFailure) {
                throw reject(TicketError.PROFILE_RESOLUTION_FAILED);
            }
            var result =
                    new VerifyTicketResult(
                            profile,
                            new AuthenticatedIdentity(claims.identity(), node.id()),
                            claims.jti());
            return replay.consume(
                            claims.issuerNodeId(),
                            claims.jti(),
                            claims.targetNodeId(),
                            claims.expiresAt())
                    .handle(
                            (decision, failure) -> {
                                if (failure != null || decision == null) {
                                    throw reject(TicketError.REPLAY_UNAVAILABLE);
                                }
                                return switch (decision) {
                                    case ACCEPTED -> result;
                                    case REPLAYED -> throw reject(TicketError.REPLAYED);
                                    case UNAVAILABLE ->
                                            throw reject(TicketError.REPLAY_UNAVAILABLE);
                                };
                            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void validateTimes(TicketClaims claims) {
        Instant now = clock.instant();
        Duration skew = TicketTiming.CLOCK_SKEW;
        Duration lifetime = Duration.between(claims.issuedAt(), claims.expiresAt());
        if (lifetime.isNegative()
                || lifetime.isZero()
                || lifetime.compareTo(ticket.maxLifetime()) > 0
                || claims.notBefore().isBefore(claims.issuedAt())
                || !claims.notBefore().isBefore(claims.expiresAt())) {
            throw reject(TicketError.INVALID_LIFETIME);
        }
        try {
            if (claims.issuedAt().isAfter(now.plus(skew))
                    || claims.notBefore().isAfter(now.plus(skew))) {
                throw reject(TicketError.NOT_YET_VALID);
            }
            if (!now.isBefore(claims.expiresAt().plus(skew))) {
                throw reject(TicketError.EXPIRED);
            }
        } catch (java.time.DateTimeException | ArithmeticException invalidTime) {
            throw reject(TicketError.INVALID_LIFETIME);
        }
    }

    private static boolean supported(AuthenticationSourceType type) {
        return switch (type) {
            case MOJANG, OFFLINE_AUTH, OFFLINE_RAW -> true;
            // TODO: Implement Yggdrasil and Floodgate identity flows before enabling these schemes.
            case YGGDRASIL, FLOODGATE -> false;
        };
    }

    private void requireOpen() {
        if (closed.get()) {
            throw reject(TicketError.ENGINE_CLOSED);
        }
    }

    private static TicketRejectedException reject(TicketError error) {
        return new TicketRejectedException(error);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && replay != null) {
            replay.close();
        }
    }
}
