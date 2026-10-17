/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.3 and 3.9
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/chorus.schema.json
 * sync: Keep SQL routing separate from local trust and validate settings before resource creation.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import me.pectics.chorus.core.config.ConfigurationLoader.Document;
import me.pectics.chorus.core.config.model.RegistryConfiguration.Engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class SqlConfigurationTest {
    @TempDir Path directory;

    static final String REGISTRY =
            """
            {"provider":"SQL",
             "sql":{"engine":"POSTGRESQL","host":"db.example.net","port":5432,
              "database":"chorus","username":"chorus_user","password-path":"password.txt",
              "tls":{"mode":"VERIFY_FULL","ca-cert":"ca.pem"},
              "pool":{"max-connections":2,"acquire-timeout":"3s"},
              "timeouts":{"connect":"3s","query":"5s","socket":"10s"}},
             "cache":{"refresh-interval":"10s","max-stale":"2m"}}
            """;

    @ParameterizedTest
    @EnumSource(Engine.class)
    void allEnginesShareTheSameStructureAndKeepTrustLocal(Engine engine) throws Exception {
        var root = core(REGISTRY.replace("POSTGRESQL", engine.name()));
        var settings = ConfigurationReader.registry(read(root).get("registry"), directory);
        assertThat(settings.sql().engine()).isEqualTo(engine);
        assertThat(settings.config()).isNull();
        assertThat(
                        ConfigurationReader.verification(root.get("verification"), directory)
                                .trustedIssuersPath())
                .isEqualTo(directory.resolve("trusted-issuers.yml"));
        assertThat(settings.sql().passwordPath()).isEqualTo(directory.resolve("password.txt"));
        assertThat(settings.sql().tls().caCert()).isEqualTo(directory.resolve("ca.pem"));
        assertThat(settings.cache().maxStale()).isEqualTo(Duration.ofMinutes(2));
        assertThat(directory.resolve("password.txt")).doesNotExist();
    }

    @Test
    void rejectsUnknownReservedOrIncompleteOptionsInTheSchema() throws Exception {
        for (String invalid :
                List.of(
                        REGISTRY.replace("POSTGRESQL", "SQLITE"),
                        REGISTRY.replace("\"SQL\"", "\"SHARED_SQL\""),
                        REGISTRY.replace("\"SQL\"", "\"CONFIG\""),
                        REGISTRY.replace("\"VERIFY_FULL\"", "\"DISABLED\""),
                        REGISTRY.replace("\"database\":", "\"schema\":\"public\",\"database\":"),
                        REGISTRY.replace(
                                "\"username\":", "\"admin-username\":\"admin\",\"username\":"),
                        REGISTRY.replace(
                                "\"provider\":",
                                "\"config\":{\"issuers-path\":\"trusted-issuers.yml\"},\"provider\":"),
                        REGISTRY.replace("db.example.net", "db.example.net?sslmode=disable"),
                        REGISTRY.replace("\"port\":5432", "\"port\":65536"))) {
            assertThatThrownBy(() -> read(core(invalid)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Schema validation failed");
        }
        var withoutCache = core(REGISTRY);
        ((ObjectNode) withoutCache.get("registry")).remove("cache");
        assertThatThrownBy(() -> read(withoutCache)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnusableDurationRelationshipsBeforeOpeningFiles() throws Exception {
        for (String invalid :
                List.of(
                        REGISTRY.replace("3s", "0ms"),
                        REGISTRY.replace("3s", "0.001ms"),
                        REGISTRY.replace("3s", "2147483648ms"),
                        REGISTRY.replace(
                                "\"acquire-timeout\":\"3s\"", "\"acquire-timeout\":\"250ms\""),
                        REGISTRY.replace("\"max-stale\":\"2m\"", "\"max-stale\":\"10s\""))) {
            assertThatThrownBy(
                            () ->
                                    ConfigurationReader.registry(
                                            read(core(invalid)).get("registry"), directory))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private ObjectNode core(String registry) {
        var json = JsonMapper.shared();
        var core = (ObjectNode) json.readTree(ConfigurationSchemaTest.CORE);
        core.set("registry", json.readTree(registry));
        return core;
    }

    private tools.jackson.databind.JsonNode read(ObjectNode root) throws Exception {
        var file = directory.resolve("chorus.yml");
        Files.writeString(file, JsonMapper.shared().writeValueAsString(root));
        return ConfigurationLoader.read(file, Document.CORE);
    }
}
