/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.8, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/staticconfig/StaticRegistries.java
 * sync: Validate complete static configuration before publishing snapshots or opening a receiver.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.api.DefaultChorusCoreEngine;
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
import me.pectics.chorus.core.strategy.IssuerTrustPolicy;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Blocking startup/configuration I/O. Invoke outside platform event loops; hot-path reads stay
 * local.
 */
public final class CoreBootstrap {
    private CoreBootstrap() {}

    public static ChorusCoreEngine open(Path configurationFile)
            throws IOException, GeneralSecurityException, SQLException {
        return open(configurationFile, Clock.systemUTC());
    }

    /** Operational durations and capacities must be explicitly provided; no unverified defaults. */
    public static ChorusCoreEngine open(Path configurationFile, Clock clock)
            throws IOException, GeneralSecurityException, SQLException {
        Path base = configurationFile.toAbsolutePath().normalize().getParent();
        var root = read(configurationFile);
        fields(
                root,
                "node",
                "cookie-key",
                "crypto",
                "registry",
                "verification",
                "replay",
                "issuance");
        var node = map(root.get("node"));
        fields(node, "id", "display-name", "role", "capabilities");
        if (node.containsKey("role")
                && !Set.of("GATEWAY", "BACKEND", "PEER").contains(text(node, "role"))) {
            throw invalid("node.role");
        }
        var capabilities = map(node.get("capabilities"));
        fields(capabilities, "sender", "receiver");
        boolean sender = bool(capabilities, "sender");
        boolean receiver = bool(capabilities, "receiver");
        var crypto = map(root.get("crypto"));
        fields(
                crypto,
                "algorithm",
                "key-id",
                "ticket-ttl",
                "max-ticket-lifetime",
                "clock-skew",
                "max-ticket-bytes",
                "max-property-bytes",
                "compress-payload",
                "private-key-file",
                "public-key-file");
        if (!text(crypto, "algorithm").equals("ED25519")
                || crypto.containsKey("compress-payload") && bool(crypto, "compress-payload")) {
            throw invalid("crypto.algorithm or compression");
        }
        var verification = map(root.get("verification"));
        fields(verification, "require-transfer-intent", "identity-strategy", "ip-binding-mode");
        if (verification.containsKey("ip-binding-mode")
                && !text(verification, "ip-binding-mode").equals("DISABLED")) {
            throw invalid("verification.ip-binding-mode");
        }
        var configuration =
                new CoreConfiguration(
                        text(node, "id"),
                        sender,
                        receiver,
                        text(root, "cookie-key"),
                        duration(crypto, "ticket-ttl"),
                        duration(crypto, "max-ticket-lifetime"),
                        duration(crypto, "clock-skew"),
                        integer(crypto, "max-ticket-bytes"),
                        integer(crypto, "max-property-bytes"),
                        bool(verification, "require-transfer-intent"),
                        IdentityStrategy.valueOf(text(verification, "identity-strategy")),
                        sender ? policy(map(root.get("issuance"))) : null);
        var registry = map(root.get("registry"));
        fields(registry, "mode", "static");
        if (!text(registry, "mode").equals("STATIC")) {
            throw invalid("Only STATIC directories are implemented in Phase 1");
        }
        var files = map(registry.get("static"));
        fields(files, "servers-file", "trust-file");
        var registries =
                loadRegistries(
                        base.resolve(text(files, "servers-file")),
                        base.resolve(text(files, "trust-file")),
                        clock);
        if (!registries.snapshot().servers().containsKey(configuration.nodeId())
                || sender
                        && !registries
                                .snapshot()
                                .servers()
                                .keySet()
                                .containsAll(configuration.issuancePolicy().allowedTargets())) {
            throw invalid("Node and issuance targets must exist in the directory");
        }
        SigningKey key =
                sender
                        ? Ed25519Keys.load(
                                text(crypto, "key-id"),
                                base.resolve(text(crypto, "private-key-file")),
                                base.resolve(text(crypto, "public-key-file")))
                        : null;
        SqliteReplayGuard guard = null;
        if (receiver) {
            var replay = map(root.get("replay"));
            fields(replay, "mode", "file", "capacity", "busy-timeout");
            if (!text(replay, "mode").equals("LOCAL_SQLITE")) {
                throw invalid("Only LOCAL_SQLITE replay is implemented in Phase 1");
            }
            guard =
                    new SqliteReplayGuard(
                            base.resolve(text(replay, "file")),
                            configuration.nodeId(),
                            configuration.clockSkew(),
                            integer(replay, "capacity"),
                            duration(replay, "busy-timeout"),
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

    /** Key paths in the trust file are relative to that file, not the process working directory. */
    public static StaticRegistries loadRegistries(Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var approved = readRegistries(serversFile, trustFile, clock);
        return new StaticRegistries(approved.directory(), approved.issuers());
    }

    /** Failed parsing, key loading, or validation preserves the previous approved snapshot. */
    public static void reloadRegistries(
            StaticRegistries registries, Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var approved = readRegistries(serversFile, trustFile, clock);
        registries.replace(approved.directory(), approved.issuers());
    }

    private static Approved readRegistries(Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var serverRoot = read(serversFile);
        fields(serverRoot, "servers");
        var servers = new HashMap<String, ServerNode>();
        for (var entry : map(serverRoot.get("servers")).entrySet()) {
            var value = map(entry.getValue());
            fields(value, "host", "port", "display-name", "tags", "metadata");
            servers.put(
                    entry.getKey(),
                    new ServerNode(
                            entry.getKey(),
                            value.containsKey("display-name")
                                    ? text(value, "display-name")
                                    : entry.getKey(),
                            text(value, "host"),
                            integer(value, "port"),
                            value.containsKey("tags") ? strings(value, "tags") : Set.of(),
                            value.containsKey("metadata")
                                    ? stringMap(value.get("metadata"))
                                    : Map.of()));
        }
        var trustRoot = read(trustFile);
        fields(trustRoot, "trusted-issuers");
        var issuers = new ArrayList<TrustedIssuer>();
        Path base = trustFile.toAbsolutePath().normalize().getParent();
        for (var entry : map(trustRoot.get("trusted-issuers")).entrySet()) {
            var value = map(entry.getValue());
            fields(
                    value,
                    "keys",
                    "enabled",
                    "allowed-auth-types",
                    "allowed-providers",
                    "allowed-targets");
            var policy = policy(value);
            if (!(value.get("keys") instanceof List<?> keys) || keys.isEmpty()) {
                throw invalid("trusted-issuers.keys");
            }
            for (var rawKey : keys) {
                var key = map(rawKey);
                fields(key, "kid", "public-key-file", "enabled");
                boolean enabled =
                        (!value.containsKey("enabled") || bool(value, "enabled"))
                                && (!key.containsKey("enabled") || bool(key, "enabled"));
                issuers.add(
                        new TrustedIssuer(
                                entry.getKey(),
                                new VerificationKey(
                                        text(key, "kid"),
                                        Ed25519Keys.loadPublic(
                                                base.resolve(text(key, "public-key-file")))),
                                enabled,
                                policy));
            }
        }
        return new Approved(
                new RegistrySnapshot(servers, clock.instant(), false), List.copyOf(issuers));
    }

    private static IssuerTrustPolicy policy(Map<String, Object> value) {
        if (!value.containsKey("keys")) {
            fields(value, "allowed-auth-types", "allowed-providers", "allowed-targets");
        }
        var types = new HashSet<AuthType>();
        for (String type : strings(value, "allowed-auth-types")) {
            types.add(AuthType.valueOf(type));
        }
        return new IssuerTrustPolicy(
                types, strings(value, "allowed-providers"), strings(value, "allowed-targets"));
    }

    private static Map<String, Object> read(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(1048577);
        }
        if (bytes.length > 1048576) {
            throw invalid("Configuration exceeds size limit");
        }
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(12);
        options.setCodePointLimit(1048576);
        String yaml =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        try {
            return map(new Yaml(new SafeConstructor(options)).load(yaml));
        } catch (org.yaml.snakeyaml.error.YAMLException failure) {
            throw invalid("Invalid YAML configuration");
        }
    }

    private static void fields(Map<String, Object> object, String... allowed) {
        if (!Set.of(allowed).containsAll(object.keySet())) {
            throw invalid("Unknown configuration field");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw invalid("Expected a mapping with string keys");
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, String> stringMap(Object value) {
        var result = new HashMap<String, String>();
        map(value)
                .forEach(
                        (key, item) -> {
                            if (!(item instanceof String text)) {
                                throw invalid("Expected string metadata");
                            }
                            result.put(key, text);
                        });
        return Map.copyOf(result);
    }

    private static String text(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof String value) || value.isBlank()) {
            throw invalid(field);
        }
        return value;
    }

    private static int integer(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof Integer value)) {
            throw invalid(field);
        }
        return value;
    }

    private static boolean bool(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof Boolean value)) {
            throw invalid(field);
        }
        return value;
    }

    private static Set<String> strings(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof List<?> list)) {
            throw invalid(field);
        }
        var result = new HashSet<String>();
        for (var item : list) {
            if (!(item instanceof String value) || value.isBlank() || !result.add(value)) {
                throw invalid(field);
            }
        }
        return Set.copyOf(result);
    }

    private static Duration duration(Map<String, Object> object, String field) {
        String value = text(object, field);
        if (value.matches("[0-9]+(ms|s|m|h)")) {
            int suffix = value.endsWith("ms") ? 2 : 1;
            long amount = Long.parseLong(value.substring(0, value.length() - suffix));
            return switch (value.substring(value.length() - suffix)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> throw invalid(field);
            };
        }
        return Duration.parse(value);
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid Core configuration: " + field);
    }
}
