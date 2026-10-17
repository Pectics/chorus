/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/chorus.schema.json
 * sync: Convert schema-approved values without maintaining business fields or format allowlists.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Shared value parsing after schema validation; does not select business fields or perform I/O. */
public final class ConfigurationValueParser {
    private ConfigurationValueParser() {}

    /**
     * Returns an immutable string set from a validated array; a null array throws
     * NullPointerException.
     */
    public static Set<String> strings(JsonNode value) {
        var result = new HashSet<String>();
        value.forEach(item -> result.add(item.asString()));
        return Set.copyOf(result);
    }

    /**
     * Resolves an optional validated path field; returns null when absent and never accesses files.
     */
    public static Path optionalPath(JsonNode value, String field, Path base) {
        return value.has(field) ? base.resolve(value.get(field).asString()) : null;
    }

    /**
     * Converts schema-approved duration syntax; unrepresentable values throw
     * IllegalArgumentException.
     */
    public static Duration duration(JsonNode node) {
        String value = node.asString();
        try {
            if (Character.isDigit(value.charAt(0))) {
                int suffix = value.endsWith("ms") ? 2 : 1;
                long amount = Long.parseLong(value.substring(0, value.length() - suffix));
                return switch (value.substring(value.length() - suffix)) {
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    default ->
                            throw new IllegalStateException(
                                    "Unsupported schema-approved duration unit");
                };
            }
            return Duration.parse(value);
        } catch (DateTimeException | ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Configuration duration cannot be represented at runtime");
        }
    }
}
