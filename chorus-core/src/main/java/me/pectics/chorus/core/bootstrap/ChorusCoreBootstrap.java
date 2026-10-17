/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.7, 3.8, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationSectionLoader.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/registry/config/ConfigServerNodeRegistryLoader.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/trust/config/ConfigTrustedIssuerLoader.java
 * sync: Assemble independent sections only after value conversion; load approved keys before opening replay state.
 * @endcontext
 */
package me.pectics.chorus.core.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import me.pectics.chorus.core.api.ChorusCoreEngine;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader.ConfigurationDocumentType;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.TicketCryptoConfiguration;
import me.pectics.chorus.core.crypto.TicketSigner;
import me.pectics.chorus.core.crypto.config.TicketCryptoConfigurationLoader;
import me.pectics.chorus.core.engine.DefaultChorusCoreEngine;
import me.pectics.chorus.core.node.ServerNodeConfiguration;
import me.pectics.chorus.core.node.config.ServerNodeConfigurationLoader;
import me.pectics.chorus.core.registry.RegistryConfiguration;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;
import me.pectics.chorus.core.registry.config.ConfigServerNodeRegistryLoader;
import me.pectics.chorus.core.registry.config.RegistryConfigurationLoader;
import me.pectics.chorus.core.registry.file.FileServerNodeRegistry;
import me.pectics.chorus.core.registry.sql.JdbcServerNodeStore;
import me.pectics.chorus.core.registry.sql.SqlServerNodeRegistry;
import me.pectics.chorus.core.replay.ReplayGuardConfiguration;
import me.pectics.chorus.core.replay.config.ReplayGuardConfigurationLoader;
import me.pectics.chorus.core.replay.local.LocalReplayGuard;
import me.pectics.chorus.core.ticket.TicketConfiguration;
import me.pectics.chorus.core.ticket.TicketIssuanceConfiguration;
import me.pectics.chorus.core.ticket.TicketVerificationConfiguration;
import me.pectics.chorus.core.ticket.config.TicketConfigurationLoader;
import me.pectics.chorus.core.ticket.config.TicketIssuanceConfigurationLoader;
import me.pectics.chorus.core.ticket.config.TicketVerificationConfigurationLoader;
import me.pectics.chorus.core.trust.config.ConfigTrustedIssuerLoader;
import me.pectics.chorus.core.trust.file.FileTrustedIssuerStore;

/** Blocking startup I/O and resource assembly. Invoke outside platform event loops. */
public final class ChorusCoreBootstrap {
    private ChorusCoreBootstrap() {}

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
        var root =
                new YamlConfigurationLoader(ConfigurationDocumentType.CORE).load(configurationFile);
        ReplayGuardConfigurationLoader.requireImplementedOptions(root.get("replay-guard"));
        Path base =
                Objects.requireNonNull(
                        configurationFile.toAbsolutePath().normalize().getParent(),
                        "Configuration file must have a parent directory");
        var node = new ServerNodeConfigurationLoader().load(root.get("node"), base);
        var ticket = new TicketConfigurationLoader().load(root.get("ticket"), base);
        var issuance =
                node.role().sender()
                        ? new TicketIssuanceConfigurationLoader().load(root.get("issuance"), base)
                        : null;
        var verification =
                new TicketVerificationConfigurationLoader().load(root.get("verification"), base);
        var crypto = new TicketCryptoConfigurationLoader().load(root.get("crypto"), base);
        var registry = new RegistryConfigurationLoader().load(root.get("registry"), base);
        var replay =
                node.role().receiver()
                        ? new ReplayGuardConfigurationLoader().load(root.get("replay-guard"), base)
                        : null;
        return assemble(node, ticket, issuance, verification, crypto, registry, replay, clock);
    }

    private static ChorusCoreEngine assemble(
            ServerNodeConfiguration node,
            TicketConfiguration ticket,
            TicketIssuanceConfiguration issuance,
            TicketVerificationConfiguration verification,
            TicketCryptoConfiguration crypto,
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
            var trust = new ConfigTrustedIssuerLoader().load(verification.trustedIssuersPath());
            ServerNodeRegistrySnapshot snapshot;
            var requiredNodes = new HashSet<String>();
            requiredNodes.add(node.id());
            if (node.role().sender()) requiredNodes.addAll(issuance.policy().allowedTargets());
            if (registry.provider() == RegistryConfiguration.Provider.CONFIG) {
                snapshot =
                        new ConfigServerNodeRegistryLoader(clock)
                                .load(registry.config().serversPath());
            } else {
                sql =
                        new SqlServerNodeRegistry(
                                JdbcServerNodeStore.open(registry.sql()),
                                registry.cache(),
                                requiredNodes,
                                clock);
                snapshot = sql.snapshot();
            }
            if (!snapshot.serverNodes().keySet().containsAll(requiredNodes)) {
                throw new IllegalArgumentException(
                        "Node and issuance targets must exist in the server node registry");
            }
            TicketSigner signer = node.role().sender() ? loadSigner(crypto) : null;
            var issuers = new FileTrustedIssuerStore(ConfigTrustedIssuerLoader.loadIssuers(trust));
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
                            sql == null ? new FileServerNodeRegistry(snapshot) : sql,
                            issuers,
                            signer,
                            guard,
                            clock);
            return sql == null ? engine : new BootstrapManagedChorusCoreEngine(engine, sql);
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

    private static TicketSigner loadSigner(TicketCryptoConfiguration crypto)
            throws IOException, GeneralSecurityException {
        return switch (crypto) {
            case TicketCryptoConfiguration.Ed25519 keys ->
                    Ed25519.loadSigner(keys.privateKey(), keys.publicKey());
            case TicketCryptoConfiguration.HmacSha256 keys -> HmacSha256.loadOrCreate(keys.key());
        };
    }
}
