/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.1 and 3.2
 * related: chorus-core/src/main/java/me/pectics/chorus/core/registry/file/FileServerNodeRegistry.java
 * sync: Keep hot-path reads pure and replacement atomic for the CONFIG registry provider.
 * @endcontext
 */
package me.pectics.chorus.core.registry.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import me.pectics.chorus.core.TestSupport;
import me.pectics.chorus.core.registry.ServerNodeRegistrySnapshot;
import org.junit.jupiter.api.Test;

class FileServerNodeRegistryTest {
    @Test
    void replacesWholeSnapshotsAndNeverExposesMutableNodeMaps() {
        var first =
                new ServerNodeRegistrySnapshot(
                        Map.of("backend", TestSupport.node("backend")), TestSupport.NOW, false);
        var registry = new FileServerNodeRegistry(first);
        assertThat(registry.snapshot()).isSameAs(first);
        assertThat(registry.find("backend")).contains(TestSupport.node("backend"));
        assertThat(registry.find("missing")).isEmpty();
        assertThatThrownBy(() -> registry.snapshot().serverNodes().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        var second =
                new ServerNodeRegistrySnapshot(
                        Map.of("gateway", TestSupport.node("gateway")), TestSupport.NOW, true);
        registry.replace(second);
        assertThat(registry.snapshot()).isSameAs(second);
        assertThat(registry.find("backend")).isEmpty();
        assertThat(registry.find("gateway")).contains(TestSupport.node("gateway"));
    }

    @Test
    void rejectsNullOrInconsistentSnapshotsWithoutChangingState() {
        var initial =
                new ServerNodeRegistrySnapshot(
                        Map.of("backend", TestSupport.node("backend")), TestSupport.NOW, false);
        var registry = new FileServerNodeRegistry(initial);
        assertThatNullPointerException().isThrownBy(() -> new FileServerNodeRegistry(null));
        assertThatNullPointerException().isThrownBy(() -> registry.replace(null));
        assertThat(registry.snapshot()).isSameAs(initial);
        assertThatThrownBy(
                        () ->
                                registry.replace(
                                        new ServerNodeRegistrySnapshot(
                                                Map.of("other", TestSupport.node("backend")),
                                                TestSupport.NOW,
                                                false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.snapshot()).isSameAs(initial);
    }
}
