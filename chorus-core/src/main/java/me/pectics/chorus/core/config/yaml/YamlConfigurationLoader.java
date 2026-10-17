/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/chorus.schema.json;
 *          chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/servers.schema.json;
 *          chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/trusted-issuers.schema.json
 * sync: Keep format selection aligned with the independently versioned document schemas.
 * @endcontext
 */
package me.pectics.chorus.core.config.yaml;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import me.pectics.chorus.core.config.ConfigurationLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Shared bounded YAML document loading for configuration-backed components. Validates each file
 * against its complete schema before returning a caller-owned JSON tree. Calls may block on file
 * I/O and must run outside platform event loops; concurrent calls use independent parser state.
 */
public final class YamlConfigurationLoader implements ConfigurationLoader<JsonNode> {
    private final ConfigurationDocumentType document;
    private static final int MAX_BYTES = 1_048_576;
    private static final SchemaRegistry SCHEMAS =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** Supported file contracts, each backed by an independently versioned classpath schema. */
    public enum ConfigurationDocumentType {
        CORE("chorus"),
        SERVERS("servers"),
        TRUSTED_ISSUERS("trusted-issuers");

        private final String resource;
        private final Schema schema;

        ConfigurationDocumentType(String name) {
            resource = "/me/pectics/chorus/core/config/schema/v1/" + name + ".schema.json";
            schema = SCHEMAS.getSchema(SchemaLocation.of("classpath:" + resource));
        }

        /** Returns the versioned classpath resource identifying this file contract. */
        public String schemaResource() {
            return resource;
        }
    }

    /** Selects a complete document contract; a null selection throws NullPointerException. */
    public YamlConfigurationLoader(ConfigurationDocumentType document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    /**
     * Reads a UTF-8 file under the size, depth, alias, and JSON-type limits. Null inputs throw
     * NullPointerException, document violations throw IllegalArgumentException with sanitized
     * file/field diagnostics, and file or character-decoding failures throw IOException.
     */
    @Override
    public JsonNode load(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid(file, "Configuration exceeds size limit");
        }
        String source =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(12);
        options.setCodePointLimit(MAX_BYTES);
        JsonNode root;
        try {
            root = json(new Yaml(new JsonNumberYamlConstructor(options)).load(source), file);
        } catch (YAMLException failure) {
            // YAML diagnostics may include source text containing credentials or key material.
            throw invalid(file, "Invalid YAML configuration");
        }
        var errors = document.schema.validate(root);
        if (!errors.isEmpty()) {
            String details =
                    errors.stream()
                            .map(
                                    error ->
                                            error.getInstanceLocation()
                                                    + " ("
                                                    + error.getKeyword()
                                                    + ")")
                            .distinct()
                            .sorted()
                            .limit(8)
                            .collect(Collectors.joining(", "));
            throw invalid(file, "Schema validation failed: " + details);
        }
        return root;
    }

    private static JsonNode json(Object value, Path file) {
        var nodes = JsonNodeFactory.instance;
        return switch (value) {
            case null -> nodes.nullNode();
            case String text -> nodes.stringNode(text);
            case Boolean flag -> nodes.booleanNode(flag);
            case Integer number -> nodes.numberNode(number);
            case Long number -> nodes.numberNode(number);
            case BigInteger number -> nodes.numberNode(number);
            case BigDecimal number -> nodes.numberNode(number);
            case List<?> values -> {
                var result = nodes.arrayNode();
                values.forEach(item -> result.add(json(item, file)));
                yield result;
            }
            case Map<?, ?> values -> {
                var result = nodes.objectNode();
                for (var entry : values.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw invalid(file, "Expected string mapping keys");
                    }
                    result.set(key, json(entry.getValue(), file));
                }
                yield result;
            }
            default -> throw invalid(file, "YAML value is not a JSON type");
        };
    }

    private static final class JsonNumberYamlConstructor extends SafeConstructor {
        JsonNumberYamlConstructor(LoaderOptions options) {
            super(options);
            // Preserve decimal precision so a fraction cannot round into a valid integer setting.
            yamlConstructors.put(
                    Tag.FLOAT,
                    new ConstructYamlFloat() {
                        @Override
                        public Object construct(Node node) {
                            try {
                                return new BigDecimal(
                                        constructScalar((ScalarNode) node).replace("_", ""));
                            } catch (NumberFormatException failure) {
                                throw new YAMLException("Expected a finite JSON number");
                            }
                        }
                    });
        }
    }

    private static IllegalArgumentException invalid(Path file, String reason) {
        return new IllegalArgumentException("Invalid Core configuration " + file + ": " + reason);
    }
}
