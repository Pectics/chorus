/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1, 3.2, and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/config/ConfigServerNodeRegistryLoader.java
 * sync: Keep file-backed registry snapshots complete, immutable, and resolved without DNS.
 * @endcontext
 */
package me.pectics.chorus.core.registry.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import me.pectics.chorus.core.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigServerNodeRegistryLoaderTest {
    @TempDir Path directory;

    @Test
    void loadsDisplayNamesAndDefaultsThemToNodeIdsWithoutResolvingHosts() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("servers.yml"),
                        """
                        gateway:
                          address: gateway.example.net:25565
                          display-name: Gateway
                        backend:
                          address: "[2001:db8::1]:25566"
                        """);
        var snapshot =
                new ConfigServerNodeRegistryLoader(
                                java.time.Clock.fixed(TestSupport.NOW, java.time.ZoneOffset.UTC))
                        .load(file);
        assertThat(snapshot.refreshedAt()).isEqualTo(TestSupport.NOW);
        assertThat(snapshot.stale()).isFalse();
        assertThat(snapshot.serverNodes().get("gateway").displayName()).isEqualTo("Gateway");
        assertThat(snapshot.serverNodes().get("gateway").host()).isEqualTo("gateway.example.net");
        assertThat(snapshot.serverNodes().get("backend").displayName()).isEqualTo("backend");
        assertThat(snapshot.serverNodes().get("backend").host()).isEqualTo("2001:db8::1");
        assertThat(snapshot.serverNodes().get("backend").port()).isEqualTo(25566);
        assertThatThrownBy(() -> snapshot.serverNodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsIncompleteEntriesWithoutPublishingAnything() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("servers.yml"), "backend: {address: localhost:70000}\n");
        assertThatThrownBy(
                        () ->
                                new ConfigServerNodeRegistryLoader(java.time.Clock.systemUTC())
                                        .load(file))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
