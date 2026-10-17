/*
 * @context
 * refs: ARCHITECTURE.md, section 3.8
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/ReplayGuard.java
 * sync: Complete policy and profile checks before durable consumption; never compensate a consumed ticket.
 * @endcontext
 */
package me.pectics.chorus.core.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import me.pectics.chorus.core.config.model.IssuanceConfiguration;
import me.pectics.chorus.core.config.model.NodeConfiguration;
import me.pectics.chorus.core.config.model.TicketConfiguration;
import me.pectics.chorus.core.config.model.VerificationConfiguration;
import me.pectics.chorus.core.crypto.TicketCodec;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.model.AuthenticatedIdentity;
import me.pectics.chorus.core.model.ReceiverLoginContext;
import me.pectics.chorus.core.model.TicketClaims;
import me.pectics.chorus.core.policy.IdentityPolicy;
import me.pectics.chorus.core.policy.ResolvedPlayerProfile;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.replay.ReplayGuard;
import me.pectics.chorus.core.trust.TrustedIssuerStore;

/**
 * Phase-1 engine. Takes ownership of ReplayGuard; other injected collaborators remain caller-owned.
 * Sender roles require a signer; receiver-only nodes may pass null. Signing material is fixed for
 * this engine's lifetime; trusted verification snapshots can still be replaced independently.
 */
public final class DefaultChorusCoreEngine implements ChorusCoreEngine {
    private final NodeConfiguration node;
    private final TicketConfiguration ticket;
    private final IssuanceConfiguration issuance;
    private final VerificationConfiguration verification;
    private final ServerNodeRegistry servers;
    private final TrustedIssuerStore issuers;
    private final TicketSigner signer;
    private final ReplayGuard replay;
    private final Clock clock;
    private final TicketCodec codec;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Accepts independent runtime sections and already assembled collaborators, without file I/O.
     * Sender roles require issuance settings and a signer; their TTL must not exceed verification's
     * maximum lifetime. Receiver roles require a replay guard. Missing required arguments throw
     * NullPointerException; an excessive TTL throws IllegalArgumentException. Issuance may be null
     * for receiver-only nodes. Replay ownership transfers only after construction succeeds.
     */
    public DefaultChorusCoreEngine(
            NodeConfiguration node,
            TicketConfiguration ticket,
            IssuanceConfiguration issuance,
            VerificationConfiguration verification,
            ServerNodeRegistry servers,
            TrustedIssuerStore issuers,
            TicketSigner signer,
            ReplayGuard replay,
            Clock clock) {
        this.node = Objects.requireNonNull(node, "node");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.issuance = issuance;
        this.verification = Objects.requireNonNull(verification, "verification");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.issuers = Objects.requireNonNull(issuers, "issuers");
        this.signer = signer;
        this.replay = replay;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec =
                new TicketCodec(
                        ticket.maxPayloadBytes(), ticket.maxPropertyBytes(), ticket.compression());
        if (node.role().sender()) {
            Objects.requireNonNull(issuance, "Sender requires issuance settings");
            if (issuance.ttl().compareTo(verification.maxLifetime()) > 0) {
                throw new IllegalArgumentException("Issuance TTL exceeds maximum ticket lifetime");
            }
            Objects.requireNonNull(signer, "Sender requires a ticket signer");
        }
        if (node.role().receiver()) {
            Objects.requireNonNull(replay, "Receiver requires a replay guard");
        }
    }

    @Override
    public ServerNodeRegistry getServerRegistry() {
        return servers;
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
            me.pectics.chorus.core.registry.ServerNode target;
            try {
                target =
                        servers.find(request.targetNodeId())
                                .orElseThrow(() -> reject(TicketError.UNKNOWN_TARGET));
            } catch (IllegalStateException unavailable) {
                throw reject(TicketError.SERVER_LIST_UNAVAILABLE);
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
            return CompletableFuture.completedFuture(
                    new IssueTicketResult(
                            target,
                            ticket.cookieKey(),
                            codec.encode(claims, signer),
                            claims.jti(),
                            claims.expiresAt()));
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
            if (verification.identityPolicy() == IdentityPolicy.STRICT_ONLINE_MATCH) {
                var verified = login.independentlyVerified();
                var identity = claims.identity();
                if (verified == null
                        || identity.authType() != AuthType.MOJANG
                        || !identity.authProvider().equals(verified.authProvider())
                        || !identity.providerSubject().equals(verified.providerSubject())
                        || !identity.sourceUuid().equals(verified.verifiedUuid())) {
                    throw reject(TicketError.EXTERNAL_IDENTITY_MISMATCH);
                }
            }
            ResolvedPlayerProfile profile;
            try {
                profile = verification.identityPolicy().resolve(claims.identity());
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
                                    case CAPACITY_EXCEEDED ->
                                            throw reject(TicketError.REPLAY_CAPACITY_EXCEEDED);
                                };
                            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void validateTimes(TicketClaims claims) {
        Instant now = clock.instant();
        Duration skew = verification.clockSkew();
        Duration lifetime = Duration.between(claims.issuedAt(), claims.expiresAt());
        if (lifetime.isNegative()
                || lifetime.isZero()
                || lifetime.compareTo(verification.maxLifetime()) > 0
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

    private static boolean supported(AuthType type) {
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
