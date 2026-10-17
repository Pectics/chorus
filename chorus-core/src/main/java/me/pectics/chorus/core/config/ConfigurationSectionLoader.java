/*
 * @context
 * refs: ARCHITECTURE.md, sections 2.1 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/config/ConfigurationValueParser.java
 * sync: Business loaders own section conversion; schema validation precedes every conversion.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.nio.file.Path;
import tools.jackson.databind.JsonNode;

/** Business-specific conversion of a schema-approved configuration section into its own model. */
@FunctionalInterface
public interface ConfigurationSectionLoader<T> {
    /**
     * Loads a complete, already schema-validated section, resolving relative paths against base.
     * Performs no I/O or runtime publication. Missing required values throw NullPointerException;
     * unsupported/domain-invalid values throw IllegalArgumentException, and numeric overflow may
     * throw ArithmeticException. Implementations are stateless and safe concurrently when callers
     * do not mutate the input tree.
     */
    T load(JsonNode section, Path base);
}
