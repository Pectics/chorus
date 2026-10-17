/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationSectionLoader.java
 * sync: Keep shared file-loading contracts independent of business models and runtime publication.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.io.IOException;
import java.nio.file.Path;

/** Loads business-owned configuration data without publishing runtime state. */
@FunctionalInterface
public interface ConfigurationLoader<T> {
    /**
     * Reads and validates a complete file. May block; invoke outside platform event loops. Null
     * inputs throw NullPointerException, invalid documents throw IllegalArgumentException, and
     * read/decoding failures throw IOException. Implementations document their result ownership and
     * concurrency contract.
     */
    T load(Path file) throws IOException;
}
