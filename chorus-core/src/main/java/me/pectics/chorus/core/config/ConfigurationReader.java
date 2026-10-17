/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationLoader.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/config/CoreBootstrap.java
 * sync: Convert schema-approved sections without opening keys, databases, or publishing snapshots.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import me.pectics.chorus.core.config.model.CryptoConfiguration;
import me.pectics.chorus.core.config.model.IssuanceConfiguration;
import me.pectics.chorus.core.config.model.NodeConfiguration;
import me.pectics.chorus.core.config.model.NodeRole;
import me.pectics.chorus.core.config.model.RegistryConfiguration;
import me.pectics.chorus.core.config.model.ReplayGuardConfiguration;
import me.pectics.chorus.core.config.model.TicketConfiguration;
import me.pectics.chorus.core.config.model.VerificationConfiguration;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketCompression;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.policy.IdentityPolicy;
import me.pectics.chorus.core.policy.IssuancePolicy;
import me.pectics.chorus.core.policy.IssuerTrustPolicy;
import tools.jackson.databind.JsonNode;

/** Typed conversion of individual, schema-approved sections; no aggregate configuration object. */
final class ConfigurationReader {
    private ConfigurationReader() {}

    static NodeConfiguration node(JsonNode value) {
        return new NodeConfiguration(
                value.get("id").asString(),
                value.has("display-name") ? value.get("display-name").asString() : null,
                NodeRole.valueOf(value.get("role").asString()));
    }

    static TicketConfiguration ticket(JsonNode value) {
        return new TicketConfiguration(
                value.get("cookie-key").asString(),
                value.get("max-payload-bytes").asInt(),
                value.get("max-property-bytes").asInt(),
                value.has("compression")
                        ? TicketCompression.valueOf(value.get("compression").asString())
                        : TicketCompression.ZSTD);
    }

    static IssuanceConfiguration issuance(JsonNode value) {
        return new IssuanceConfiguration(
                duration(value.get("ttl")),
                new IssuancePolicy(trustPolicy(value), strings(value.get("allowed-targets"))));
    }

    static VerificationConfiguration verification(JsonNode value, Path base) {
        return new VerificationConfiguration(
                duration(value.get("max-lifetime")),
                duration(value.get("clock-skew")),
                value.get("enforce-transfer-intent").asBoolean(),
                IdentityPolicy.valueOf(value.get("identity-policy").asString()),
                base.resolve(value.get("trusted-issuers-path").asString()));
    }

    static CryptoConfiguration crypto(JsonNode value, Path base) {
        return switch (SignatureAlgorithm.valueOf(value.get("algorithm").asString())) {
            case ED25519 ->
                    new CryptoConfiguration.Ed25519(
                            optionalPath(value, "private-key", base),
                            optionalPath(value, "public-key", base));
            case HMAC_SHA256 ->
                    new CryptoConfiguration.HmacSha256(optionalPath(value, "key", base));
        };
    }

    static RegistryConfiguration registry(JsonNode value, Path base) {
        var config = value.get("config");
        return new RegistryConfiguration(
                RegistryConfiguration.Provider.valueOf(value.get("provider").asString()),
                config == null
                        ? null
                        : new RegistryConfiguration.Config(
                                base.resolve(config.get("servers-path").asString())),
                value.has("sql") ? sql(value.get("sql"), base) : null,
                value.has("cache")
                        ? new RegistryConfiguration.Cache(
                                duration(value.at("/cache/refresh-interval")),
                                duration(value.at("/cache/max-stale")))
                        : null);
    }

    private static RegistryConfiguration.Sql sql(JsonNode value, Path base) {
        return new RegistryConfiguration.Sql(
                RegistryConfiguration.Engine.valueOf(value.get("engine").asString()),
                value.get("host").asString(),
                value.get("port").asInt(),
                value.get("database").asString(),
                value.get("username").asString(),
                base.resolve(value.get("password-path").asString()),
                new RegistryConfiguration.Tls(
                        RegistryConfiguration.TlsMode.valueOf(value.at("/tls/mode").asString()),
                        optionalPath(value.get("tls"), "ca-cert", base)),
                new RegistryConfiguration.Pool(
                        value.at("/pool/max-connections").asInt(),
                        duration(value.at("/pool/acquire-timeout"))),
                new RegistryConfiguration.Timeouts(
                        duration(value.at("/timeouts/connect")),
                        duration(value.at("/timeouts/query")),
                        duration(value.at("/timeouts/socket"))));
    }

    static ReplayGuardConfiguration replayGuard(JsonNode value, Path base) {
        var backend = ReplayGuardConfiguration.Backend.valueOf(value.get("backend").asString());
        requireImplementedBackend(backend);
        var local = value.get("local");
        return new ReplayGuardConfiguration(
                backend,
                new ReplayGuardConfiguration.Local(
                        base.resolve(local.get("path").asString()),
                        local.get("max-entries").asInt(),
                        duration(local.get("busy-timeout"))));
    }

    static void requireImplementedOptions(JsonNode root) {
        var replay = root.get("replay-guard");
        if (replay != null) {
            requireImplementedBackend(
                    ReplayGuardConfiguration.Backend.valueOf(replay.get("backend").asString()));
        }
    }

    private static void requireImplementedBackend(ReplayGuardConfiguration.Backend backend) {
        switch (backend) {
            case LOCAL -> {}
            case REGISTRY -> {
                // TODO: Reuse the configured registry database for durable ticket consumption.
                throw new IllegalArgumentException(
                        "replay-guard.backend REGISTRY is not implemented");
            }
            case REDIS -> {
                // TODO: Define Redis settings and implement a durable Redis ReplayGuard.
                throw new IllegalArgumentException("replay-guard.backend REDIS is not implemented");
            }
        }
    }

    static IssuerTrustPolicy trustPolicy(JsonNode value) {
        var types = new HashSet<AuthType>();
        value.get("allowed-auth-schemes")
                .forEach(item -> types.add(AuthType.valueOf(item.asString())));
        return new IssuerTrustPolicy(types, strings(value.get("allowed-providers")));
    }

    private static Set<String> strings(JsonNode value) {
        var result = new HashSet<String>();
        value.forEach(item -> result.add(item.asString()));
        return Set.copyOf(result);
    }

    private static Path optionalPath(JsonNode value, String field, Path base) {
        return value.has(field) ? base.resolve(value.get(field).asString()) : null;
    }

    private static Duration duration(JsonNode node) {
        String value = node.asString();
        try {
            if (Character.isDigit(value.charAt(0))) {
                int suffix = value.endsWith("ms") ? 2 : 1;
                long amount = Long.parseLong(value.substring(0, value.length() - suffix));
                return switch (value.substring(value.length() - suffix)) {
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    default ->
                            throw new IllegalStateException(
                                    "Unsupported schema-approved duration unit");
                };
            }
            return Duration.parse(value);
        } catch (DateTimeException | ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Configuration duration cannot be represented at runtime");
        }
    }
}
