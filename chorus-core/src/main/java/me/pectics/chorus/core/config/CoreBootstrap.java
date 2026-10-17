/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.7, 3.8, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationSectionConverter.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/config/ServerNodeRegistryLoader.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/config/TrustedIssuerLoader.java
 * sync: Assemble independent sections only after value conversion; load approved keys before opening replay state.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.api.DefaultChorusCoreEngine;
import me.pectics.chorus.core.config.YamlConfigurationLoader.ConfigurationDocumentType;
import me.pectics.chorus.core.config.model.CryptoConfiguration;
import me.pectics.chorus.core.config.model.IssuanceConfiguration;
import me.pectics.chorus.core.config.model.NodeConfiguration;
import me.pectics.chorus.core.config.model.RegistryConfiguration;
import me.pectics.chorus.core.config.model.ReplayGuardConfiguration;
import me.pectics.chorus.core.config.model.TicketConfiguration;
import me.pectics.chorus.core.config.model.VerificationConfiguration;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;
import me.pectics.chorus.core.registry.config.ConfigServerNodeRegistry;
import me.pectics.chorus.core.registry.sql.JdbcServerNodeStore;
import me.pectics.chorus.core.registry.sql.SqlServerNodeRegistry;
import me.pectics.chorus.core.replay.local.LocalReplayGuard;
import me.pectics.chorus.core.trust.config.ConfigTrustedIssuerStore;

/** Blocking startup I/O and resource assembly. Invoke outside platform event loops. */
public final class CoreBootstrap {
    private CoreBootstrap() {}

    public static ChorusCoreEngine open(Path configurationFile)
            throws IOException, GeneralSecurityException, SQLException {
        return open(configurationFile, Clock.systemUTC());
    }

    /**
     * Loads independently typed sections with explicit operational limits. YAML and schema errors
     * throw IllegalArgumentException with the file path; schema errors also identify the field.
     * Conversion and reference checks precede resource assembly. Only sending roles load signing
     * material, and only receiving roles open durable replay state.
     */
    public static ChorusCoreEngine open(Path configurationFile, Clock clock)
            throws IOException, GeneralSecurityException, SQLException {
        Objects.requireNonNull(clock, "clock");
        var root = YamlConfigurationLoader.read(configurationFile, ConfigurationDocumentType.CORE);
        ConfigurationSectionConverter.requireImplementedOptions(root);
        Path base =
                Objects.requireNonNull(
                        configurationFile.toAbsolutePath().normalize().getParent(),
                        "Configuration file must have a parent directory");
        var node = ConfigurationSectionConverter.node(root.get("node"));
        var ticket = ConfigurationSectionConverter.ticket(root.get("ticket"));
        var issuance =
                node.role().sender()
                        ? ConfigurationSectionConverter.issuance(root.get("issuance"))
                        : null;
        var verification =
                ConfigurationSectionConverter.verification(root.get("verification"), base);
        var crypto = ConfigurationSectionConverter.crypto(root.get("crypto"), base);
        var registry = ConfigurationSectionConverter.registry(root.get("registry"), base);
        var replay =
                node.role().receiver()
                        ? ConfigurationSectionConverter.replayGuard(root.get("replay-guard"), base)
                        : null;
        return assemble(node, ticket, issuance, verification, crypto, registry, replay, clock);
    }

    private static ChorusCoreEngine assemble(
            NodeConfiguration node,
            TicketConfiguration ticket,
            IssuanceConfiguration issuance,
            VerificationConfiguration verification,
            CryptoConfiguration crypto,
            RegistryConfiguration registry,
            ReplayGuardConfiguration replay,
            Clock clock)
            throws IOException, GeneralSecurityException, SQLException {
        if (node.role().sender() && issuance.ttl().compareTo(verification.maxLifetime()) > 0) {
            throw new IllegalArgumentException("Issuance TTL exceeds maximum ticket lifetime");
        }
        SqlServerNodeRegistry sql = null;
        LocalReplayGuard guard = null;
        try {
            var trust = TrustedIssuerLoader.read(verification.trustedIssuersPath());
            ServerNodeRegistrySnapshot snapshot;
            var requiredNodes = new HashSet<String>();
            requiredNodes.add(node.id());
            if (node.role().sender()) requiredNodes.addAll(issuance.policy().allowedTargets());
            if (registry.provider() == RegistryConfiguration.Provider.CONFIG) {
                snapshot = ServerNodeRegistryLoader.read(registry.config().serversPath(), clock);
            } else {
                sql =
                        new SqlServerNodeRegistry(
                                JdbcServerNodeStore.open(registry.sql()),
                                registry.cache(),
                                requiredNodes,
                                clock);
                snapshot = sql.snapshot();
            }
            if (!snapshot.servers().keySet().containsAll(requiredNodes)) {
                throw new IllegalArgumentException(
                        "Node and issuance targets must exist in the server list");
            }
            TicketSigner signer = node.role().sender() ? loadSigner(crypto) : null;
            var issuers = new ConfigTrustedIssuerStore(trust.loadIssuers());
            if (node.role().receiver()) {
                var local = replay.local();
                guard =
                        new LocalReplayGuard(
                                local.path(),
                                node.id(),
                                verification.clockSkew(),
                                local.maxEntries(),
                                local.busyTimeout(),
                                clock);
            }
            var engine =
                    new DefaultChorusCoreEngine(
                            node,
                            ticket,
                            issuance,
                            verification,
                            sql == null ? new ConfigServerNodeRegistry(snapshot) : sql,
                            issuers,
                            signer,
                            guard,
                            clock);
            return sql == null ? engine : new ResourceOwningChorusCoreEngine(engine, sql);
        } catch (IOException
                | GeneralSecurityException
                | SQLException
                | RuntimeException
                | Error failure) {
            closeAfterFailure(guard, failure);
            closeAfterFailure(sql, failure);
            throw failure;
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception close) {
                failure.addSuppressed(close);
            }
        }
    }

    private static TicketSigner loadSigner(CryptoConfiguration crypto)
            throws IOException, GeneralSecurityException {
        return switch (crypto) {
            case CryptoConfiguration.Ed25519 keys ->
                    Ed25519.loadSigner(keys.privateKey(), keys.publicKey());
            case CryptoConfiguration.HmacSha256 keys -> HmacSha256.loadOrCreate(keys.key());
        };
    }

    /**
     * Blocking server list load. Validates servers.yml only; no trust or key files are accessed.
     */
    public static ConfigServerNodeRegistry loadServerNodeRegistry(Path serversFile, Clock clock)
            throws IOException {
        return new ConfigServerNodeRegistry(ServerNodeRegistryLoader.read(serversFile, clock));
    }

    /** Blocking server list reload. Parsing or schema failures leave the old snapshot intact. */
    public static void reloadServerNodeRegistry(
            ConfigServerNodeRegistry registry, Path serversFile, Clock clock) throws IOException {
        registry.replace(ServerNodeRegistryLoader.read(serversFile, clock));
    }

    /** Blocking local trust load. Key paths resolve against the trust file; missing keys fail. */
    public static ConfigTrustedIssuerStore loadTrustedIssuers(Path trustFile)
            throws IOException, GeneralSecurityException {
        return new ConfigTrustedIssuerStore(TrustedIssuerLoader.read(trustFile).loadIssuers());
    }

    /**
     * Blocking trust reload. Invalid documents or keys retain all old approvals; no server list
     * I/O.
     */
    public static void reloadTrustedIssuers(ConfigTrustedIssuerStore issuers, Path trustFile)
            throws IOException, GeneralSecurityException {
        issuers.replace(TrustedIssuerLoader.read(trustFile).loadIssuers());
    }
}
