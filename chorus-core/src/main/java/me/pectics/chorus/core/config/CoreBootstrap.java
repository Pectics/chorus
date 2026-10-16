/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.8, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationLoader.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/registry/staticconfig/StaticRegistries.java
 * sync: Consume schema-approved values and validate runtime references before publishing state.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.api.DefaultChorusCoreEngine;
import me.pectics.chorus.core.config.ConfigurationLoader.Document;
import me.pectics.chorus.core.crypto.Ed25519Keys;
import me.pectics.chorus.core.crypto.SigningKey;
import me.pectics.chorus.core.crypto.StaticKeyRing;
import me.pectics.chorus.core.crypto.VerificationKey;
import me.pectics.chorus.core.model.AuthType;
import me.pectics.chorus.core.registry.RegistrySnapshot;
import me.pectics.chorus.core.registry.ServerNode;
import me.pectics.chorus.core.registry.TrustedIssuer;
import me.pectics.chorus.core.registry.staticconfig.StaticRegistries;
import me.pectics.chorus.core.state.sqlite.SqliteReplayGuard;
import me.pectics.chorus.core.strategy.IdentityStrategy;
import me.pectics.chorus.core.strategy.IssuancePolicy;
import me.pectics.chorus.core.strategy.IssuerTrustPolicy;
import tools.jackson.databind.JsonNode;

/**
 * Blocking startup I/O and runtime assembly. Invoke outside platform event loops. File format
 * validation belongs to each document's schema; domain objects still protect their runtime
 * invariants, including relationships between converted durations and references across files.
 */
public final class CoreBootstrap {
    private CoreBootstrap() {}

    public static ChorusCoreEngine open(Path configurationFile)
            throws IOException, GeneralSecurityException, SQLException {
        return open(configurationFile, Clock.systemUTC());
    }

    /**
     * Loads the current file formats with explicit operational limits. YAML and schema errors throw
     * IllegalArgumentException with the file path; schema errors also identify the field.
     */
    public static ChorusCoreEngine open(Path configurationFile, Clock clock)
            throws IOException, GeneralSecurityException, SQLException {
        var root = ConfigurationLoader.read(configurationFile, Document.CORE);
        Path base = configurationFile.toAbsolutePath().normalize().getParent();
        var role = NodeRole.valueOf(root.at("/node/role").asString());
        var crypto = root.get("crypto");
        var verification = root.get("verification");
        var configuration =
                new CoreConfiguration(
                        root.at("/node/id").asString(),
                        role,
                        root.get("cookie-key").asString(),
                        duration(crypto.get("ticket-ttl")),
                        duration(crypto.get("max-ticket-lifetime")),
                        duration(crypto.get("clock-skew")),
                        crypto.get("max-ticket-bytes").asInt(),
                        crypto.get("max-property-bytes").asInt(),
                        verification.get("require-transfer-intent").asBoolean(),
                        IdentityStrategy.valueOf(verification.get("identity-strategy").asString()),
                        role.sender()
                                ? new IssuancePolicy(
                                        policy(root.get("issuance")),
                                        strings(root.at("/issuance/allowed-targets")))
                                : null);
        var files = root.at("/registry/static");
        var registries =
                loadRegistries(
                        base.resolve(files.get("servers-file").asString()),
                        base.resolve(files.get("trust-file").asString()),
                        clock);
        if (!registries.snapshot().servers().containsKey(configuration.nodeId())
                || role.sender()
                        && !registries
                                .snapshot()
                                .servers()
                                .keySet()
                                .containsAll(configuration.issuancePolicy().allowedTargets())) {
            throw new IllegalArgumentException(
                    "Node and issuance targets must exist in the directory");
        }
        SigningKey key =
                role.sender()
                        ? Ed25519Keys.load(
                                base.resolve(crypto.get("private-key-file").asString()),
                                base.resolve(crypto.get("public-key-file").asString()))
                        : null;
        SqliteReplayGuard guard = null;
        if (role.receiver()) {
            var replay = root.get("replay");
            guard =
                    new SqliteReplayGuard(
                            base.resolve(replay.get("file").asString()),
                            configuration.nodeId(),
                            configuration.clockSkew(),
                            replay.get("capacity").asInt(),
                            duration(replay.get("busy-timeout")),
                            clock);
        }
        try {
            return new DefaultChorusCoreEngine(
                    configuration,
                    registries,
                    registries,
                    new StaticKeyRing(key, registries),
                    guard,
                    clock);
        } catch (RuntimeException failure) {
            if (guard != null) {
                guard.close();
            }
            throw failure;
        }
    }

    private record Approved(RegistrySnapshot directory, List<TrustedIssuer> issuers) {}

    /** Key paths are relative to the trust file. Both files must satisfy their own schemas. */
    public static StaticRegistries loadRegistries(Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var approved = readRegistries(serversFile, trustFile, clock);
        return new StaticRegistries(approved.directory(), approved.issuers());
    }

    /** Parsing, schema, key, or reference failures leave the previous approved snapshot intact. */
    public static void reloadRegistries(
            StaticRegistries registries, Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var approved = readRegistries(serversFile, trustFile, clock);
        registries.replace(approved.directory(), approved.issuers());
    }

    private static Approved readRegistries(Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var directory = ConfigurationLoader.read(serversFile, Document.SERVERS).get("servers");
        var trust =
                ConfigurationLoader.read(trustFile, Document.TRUSTED_ISSUERS)
                        .get("trusted-issuers");
        var servers = new HashMap<String, ServerNode>();
        for (var entry : directory.properties()) {
            var value = entry.getValue();
            servers.put(
                    entry.getKey(),
                    new ServerNode(
                            entry.getKey(),
                            value.has("display-name")
                                    ? value.get("display-name").asString()
                                    : entry.getKey(),
                            value.get("host").asString(),
                            value.get("port").asInt()));
        }
        var issuers = new ArrayList<TrustedIssuer>();
        Path base = trustFile.toAbsolutePath().normalize().getParent();
        for (var entry : trust.properties()) {
            var value = entry.getValue();
            var keys = new ArrayList<VerificationKey>();
            Iterable<JsonNode> paths =
                    value.has("keys") ? value.get("keys") : List.of(value.get("key"));
            for (var key : paths) {
                keys.add(new VerificationKey(Ed25519Keys.loadPublic(base.resolve(key.asString()))));
            }
            issuers.add(
                    new TrustedIssuer(
                            entry.getKey(),
                            keys,
                            !value.has("enabled") || value.get("enabled").asBoolean(),
                            policy(value)));
        }
        return new Approved(
                new RegistrySnapshot(servers, clock.instant(), false), List.copyOf(issuers));
    }

    private static IssuerTrustPolicy policy(JsonNode value) {
        var types = new HashSet<AuthType>();
        value.get("allowed-auth-types")
                .forEach(item -> types.add(AuthType.valueOf(item.asString())));
        return new IssuerTrustPolicy(types, strings(value.get("allowed-providers")));
    }

    private static Set<String> strings(JsonNode value) {
        var result = new HashSet<String>();
        value.forEach(item -> result.add(item.asString()));
        return Set.copyOf(result);
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
