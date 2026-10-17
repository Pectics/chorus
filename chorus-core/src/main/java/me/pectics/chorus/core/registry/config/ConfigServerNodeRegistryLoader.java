/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.2, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/file/FileServerNodeRegistry.java
 * sync: Validate complete server node registry snapshots independently of issuer approvals and keys.
 * @endcontext
 */
package me.pectics.chorus.core.registry.config;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Objects;
import me.pectics.chorus.core.config.ConfigurationLoader;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader.ConfigurationDocumentType;
import me.pectics.chorus.core.node.ServerNode;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;
import me.pectics.chorus.core.registry.file.FileServerNodeRegistry;

/**
 * Loads server node registry snapshots from a node list configuration file; never loads trust or
 * keys.
 */
public final class ConfigServerNodeRegistryLoader
        implements ConfigurationLoader<ServerNodeRegistrySnapshot> {
    private final Clock clock;

    /** Selects the snapshot display clock; a null clock throws NullPointerException. */
    public ConfigServerNodeRegistryLoader(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Loads an immutable snapshot with schema and per-node validation, without publishing it. */
    @Override
    public ServerNodeRegistrySnapshot load(Path serversFile) throws IOException {
        var serversDocument =
                new YamlConfigurationLoader(ConfigurationDocumentType.SERVERS).load(serversFile);
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

    /** Loads an independent file-backed registry; performs blocking file I/O. */
    public static FileServerNodeRegistry loadRegistry(Path file, Clock clock) throws IOException {
        return new FileServerNodeRegistry(new ConfigServerNodeRegistryLoader(clock).load(file));
    }

    /**
     * Reloads atomically after complete validation; failures leave the previous snapshot intact.
     */
    public static void reloadRegistry(FileServerNodeRegistry registry, Path file, Clock clock)
            throws IOException {
        registry.replace(new ConfigServerNodeRegistryLoader(clock).load(file));
    }
}
