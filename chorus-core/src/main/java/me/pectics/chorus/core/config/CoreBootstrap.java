/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.7, 3.8, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationReader.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/config/RegistryLoader.java
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
import me.pectics.chorus.core.config.ConfigurationLoader.Document;
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
import me.pectics.chorus.core.registry.RegistrySnapshot;
import me.pectics.chorus.core.registry.config.ConfigRegistries;
import me.pectics.chorus.core.registry.sql.JdbcServerStore;
import me.pectics.chorus.core.registry.sql.SqlServerRegistry;
import me.pectics.chorus.core.replay.local.LocalReplayGuard;

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
        var root = ConfigurationLoader.read(configurationFile, Document.CORE);
        ConfigurationReader.requireImplementedOptions(root);
        Path base = configurationFile.toAbsolutePath().normalize().getParent();
        var node = ConfigurationReader.node(root.get("node"));
        var ticket = ConfigurationReader.ticket(root.get("ticket"));
        var issuance =
                node.role().sender() ? ConfigurationReader.issuance(root.get("issuance")) : null;
        var verification = ConfigurationReader.verification(root.get("verification"));
        var crypto = ConfigurationReader.crypto(root.get("crypto"), base);
        var registry = ConfigurationReader.registry(root.get("registry"), base);
        var replay =
                node.role().receiver()
                        ? ConfigurationReader.replayGuard(root.get("replay-guard"), base)
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
        var files = registry.config();
        SqlServerRegistry sql = null;
        LocalReplayGuard guard = null;
        try {
            RegistrySnapshot directory;
            RegistryLoader.LocalTrust trust;
            var requiredNodes = new HashSet<String>();
            requiredNodes.add(node.id());
            if (node.role().sender()) requiredNodes.addAll(issuance.policy().allowedTargets());
            if (registry.provider() == RegistryConfiguration.Provider.CONFIG) {
                var documents =
                        RegistryLoader.read(files.serversPath(), files.issuersPath(), clock);
                directory = documents.directory();
                trust = documents.localTrust();
            } else {
                trust = RegistryLoader.readTrust(files.issuersPath());
                sql =
                        new SqlServerRegistry(
                                JdbcServerStore.open(registry.sql()),
                                registry.cache(),
                                requiredNodes,
                                clock);
                directory = sql.snapshot();
            }
            if (!directory.servers().keySet().containsAll(requiredNodes)) {
                throw new IllegalArgumentException(
                        "Node and issuance targets must exist in the directory");
            }
            TicketSigner signer = node.role().sender() ? loadSigner(crypto) : null;
            var registries = new ConfigRegistries(directory, trust.loadIssuers());
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
                            sql == null ? registries : sql,
                            registries,
                            signer,
                            guard,
                            clock);
            return sql == null ? engine : new ManagedCoreEngine(engine, sql);
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

    /** Key paths are relative to the trust file. Both files must satisfy their own schemas. */
    public static ConfigRegistries loadRegistries(Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var documents = RegistryLoader.read(serversFile, trustFile, clock);
        return new ConfigRegistries(documents.directory(), documents.loadIssuers());
    }

    /** Parsing, schema, key, or reference failures leave the previous approved snapshot intact. */
    public static void reloadRegistries(
            ConfigRegistries registries, Path serversFile, Path trustFile, Clock clock)
            throws IOException, GeneralSecurityException {
        var documents = RegistryLoader.read(serversFile, trustFile, clock);
        registries.replace(documents.directory(), documents.loadIssuers());
    }
}
