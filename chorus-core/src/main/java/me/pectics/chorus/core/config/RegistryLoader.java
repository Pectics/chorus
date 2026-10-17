/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.2, 3.7, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/CoreBootstrap.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/registry/config/ConfigRegistries.java
 * sync: Validate both documents before loading keys; publish routing and trust together after full success.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import me.pectics.chorus.core.config.ConfigurationLoader.Document;
import me.pectics.chorus.core.crypto.Ed25519;
import me.pectics.chorus.core.crypto.HmacSha256;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketVerifier;
import me.pectics.chorus.core.registry.RegistrySnapshot;
import me.pectics.chorus.core.registry.ServerNode;
import me.pectics.chorus.core.registry.TrustedIssuer;
import tools.jackson.databind.JsonNode;

/** Validated directory documents, with trust key loading deferred until local signer assembly. */
final class RegistryLoader {
    private final RegistrySnapshot directory;
    private final JsonNode trust;
    private final Path trustFile;

    private RegistryLoader(RegistrySnapshot directory, JsonNode trust, Path trustFile) {
        this.directory = directory;
        this.trust = trust;
        this.trustFile = trustFile;
    }

    static RegistryLoader read(Path serversFile, Path trustFile, Clock clock) throws IOException {
        var directory = ConfigurationLoader.read(serversFile, Document.SERVERS);
        var trust = ConfigurationLoader.read(trustFile, Document.TRUSTED_ISSUERS);
        return new RegistryLoader(readDirectory(directory, clock), trust, trustFile);
    }

    RegistrySnapshot directory() {
        return directory;
    }

    private static RegistrySnapshot readDirectory(JsonNode directory, Clock clock) {
        var servers = new HashMap<String, ServerNode>();
        for (var entry : directory.properties()) {
            var value = entry.getValue();
            String address = value.get("address").asString();
            int separator = address.lastIndexOf(':');
            String host = address.substring(0, separator);
            if (host.startsWith("[")) {
                host = host.substring(1, host.length() - 1);
            }
            servers.put(
                    entry.getKey(),
                    new ServerNode(
                            entry.getKey(),
                            value.has("display-name")
                                    ? value.get("display-name").asString()
                                    : entry.getKey(),
                            host,
                            Integer.parseInt(address.substring(separator + 1))));
        }
        return new RegistrySnapshot(servers, clock.instant(), false);
    }

    List<TrustedIssuer> loadIssuers() throws IOException, GeneralSecurityException {
        var issuers = new ArrayList<TrustedIssuer>();
        Path base = trustFile.toAbsolutePath().normalize().getParent();
        for (var entry : trust.properties()) {
            var value = entry.getValue();
            var algorithm =
                    value.has("algorithm")
                            ? SignatureAlgorithm.valueOf(value.get("algorithm").asString())
                            : SignatureAlgorithm.ED25519;
            var verifiers = new ArrayList<TicketVerifier>();
            Iterable<JsonNode> paths =
                    value.has("keys") ? value.get("keys") : List.of(value.get("key"));
            for (var key : paths) {
                Path path = base.resolve(key.asString());
                verifiers.add(
                        switch (algorithm) {
                            case ED25519 -> Ed25519.loadVerifier(path);
                            case HMAC_SHA256 -> HmacSha256.load(path);
                        });
            }
            issuers.add(
                    new TrustedIssuer(
                            entry.getKey(),
                            verifiers,
                            !value.has("enabled") || value.get("enabled").asBoolean(),
                            ConfigurationReader.trustPolicy(value)));
        }
        return List.copyOf(issuers);
    }
}
