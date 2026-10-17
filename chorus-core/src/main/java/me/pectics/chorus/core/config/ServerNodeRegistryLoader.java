/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.2, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/config/ConfigServerNodeRegistry.java
 * sync: Validate complete server node registry snapshots independently of issuer approvals and keys.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import me.pectics.chorus.core.config.YamlConfigurationLoader.ConfigurationDocumentType;
import me.pectics.chorus.core.registry.ServerNode;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;

/**
 * Loads server node registry snapshots from a node list configuration file; never loads trust or
 * keys.
 */
final class ServerNodeRegistryLoader {
    private ServerNodeRegistryLoader() {}

    static ServerNodeRegistrySnapshot read(Path serversFile, Clock clock) throws IOException {
        var serversDocument =
                YamlConfigurationLoader.read(serversFile, ConfigurationDocumentType.SERVERS);
        var serverNodes = new HashMap<String, ServerNode>();
        for (var entry : serversDocument.properties()) {
            var value = entry.getValue();
            String address = value.get("address").asString();
            int separator = address.lastIndexOf(':');
            String host = address.substring(0, separator);
            if (host.startsWith("[")) {
                host = host.substring(1, host.length() - 1);
            }
            serverNodes.put(
                    entry.getKey(),
                    new ServerNode(
                            entry.getKey(),
                            value.has("display-name")
                                    ? value.get("display-name").asString()
                                    : entry.getKey(),
                            host,
                            Integer.parseInt(address.substring(separator + 1))));
        }
        return new ServerNodeRegistrySnapshot(serverNodes, clock.instant(), false);
    }
}
