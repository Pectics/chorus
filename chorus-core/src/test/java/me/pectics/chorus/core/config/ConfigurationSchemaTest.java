/*
 * @context
 * refs: ARCHITECTURE.md, section 3.9
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/chorus.schema.json;
 *          chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/servers.schema.json;
 *          chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/trusted-issuers.schema.json
 * sync: Validate each complete file contract directly, independently of bootstrap and domain constructors.
 * @endcontext
 */
package me.pectics.chorus.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.math.BigDecimal;
import java.util.List;
import me.pectics.chorus.core.config.ConfigurationLoader.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ConfigurationSchemaTest {
    static final String CORE =
            """
            {
              "node": {"id": "backend", "capabilities": {"sender": false, "receiver": true}},
              "cookie-key": "chorus:ticket",
              "crypto": {
                "algorithm": "ED25519", "ticket-ttl": "15s", "max-ticket-lifetime": "15s",
                "clock-skew": "2s", "max-ticket-bytes": 4096, "max-property-bytes": 2048
              },
              "registry": {"mode": "STATIC", "static": {"servers-file": "servers.yml", "trust-file": "trusted-issuers.yml"}},
              "verification": {"require-transfer-intent": true, "identity-strategy": "ADOPT_UPSTREAM"},
              "replay": {"mode": "LOCAL_SQLITE", "file": "state/replay.db", "capacity": 100, "busy-timeout": "500ms"}
            }
            """;
    static final String SERVERS =
            """
            {"servers": {"backend": {"host": "localhost", "port": 25565}}}
            """;
    static final String TRUST =
            """
            {"trusted-issuers": {"gateway": {
              "keys": [{"kid": "key-1", "public-key-file": "gateway.pub"}],
              "allowed-auth-types": ["MOJANG"], "allowed-providers": ["mojang"], "allowed-targets": ["backend"]
            }}}
            """;
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final JsonMapper JSON = JsonMapper.shared();

    @ParameterizedTest
    @EnumSource(Document.class)
    void eachFileHasACompleteVersionedSchemaAtItsOwnRoot(Document document) throws Exception {
        try (var input =
                ConfigurationSchemaTest.class.getResourceAsStream(document.schemaResource())) {
            assertThat(input).isNotNull();
            var schema = JSON.readTree(input);
            var meta =
                    REGISTRY.getSchema(
                            SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
            assertThat(meta.validate(schema)).isEmpty();
            assertThat(schema.get("$id").asString()).endsWith(":v1");
            assertThat(schema.get("type").asString()).isEqualTo("object");
            assertThat(schema.has("properties")).isTrue();
            assertThat(schema.has("$ref")).isFalse();
            assertThat(contract(document).validate(fixture(document))).isEmpty();
            for (var other : Document.values()) {
                if (other != document) {
                    assertThat(contract(document).validate(fixture(other))).isNotEmpty();
                }
            }
        }
    }

    @Test
    void capabilitiesControlRequiredSectionsWithinTheMainSchema() {
        var core = fixture(Document.CORE);
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
        ((ObjectNode) core).remove("replay");
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
        var capabilities = (ObjectNode) core.at("/node/capabilities");
        capabilities.put("receiver", false).put("sender", true);
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
        ((ObjectNode) core.get("crypto"))
                .put("key-id", "key-1")
                .put("private-key-file", "private.der")
                .put("public-key-file", "public.der");
        ((ObjectNode) core)
                .set(
                        "issuance",
                        fixture(Document.TRUSTED_ISSUERS)
                                .at("/trusted-issuers/gateway")
                                .deepCopy());
        ((ObjectNode) core.get("issuance")).remove("keys");
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
    }

    @Test
    void mainSchemaDefinesRequiredFieldsTypesLimitsAndImplementedOptions() {
        for (String invalid :
                List.of(
                        CORE.replace("\"id\": \"backend\"", "\"id\": \"bad id\""),
                        CORE.replace("\"sender\": false", "\"sender\": \"false\""),
                        CORE.replace("\"algorithm\": \"ED25519\"", "\"algorithm\": \"HMAC\""),
                        CORE.replace(
                                "\"algorithm\": \"ED25519\"",
                                "\"algorithm\": \"ED25519\", \"compress-payload\": true"),
                        CORE.replace("4096", "5121"),
                        CORE.replace("2048", "0"),
                        CORE.replace("\"capacity\": 100", "\"capacity\": 2147483648"),
                        CORE.replace("STATIC", "SHARED_SQL"),
                        CORE.replace("LOCAL_SQLITE", "LOCAL_MEMORY"),
                        CORE.replace("ADOPT_UPSTREAM", "UNKNOWN"),
                        CORE.replace(
                                "\"id\": \"backend\"", "\"id\": \"backend\", \"typo\": true"))) {
            assertThat(contract(Document.CORE).validate(JSON.readTree(invalid))).isNotEmpty();
        }
        var core = (ObjectNode) fixture(Document.CORE);
        core.remove("crypto");
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
        core = (ObjectNode) fixture(Document.CORE);
        core.set("issuance", JSON.createObjectNode());
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "15s",
                "1m",
                "1h",
                "15000ms",
                "P1D",
                "PT1H30M",
                "pt15s",
                "PT15.000S",
                "-PT-15S",
                "PT-1H120M"
            })
    void mainSchemaAcceptsSupportedWholeSecondDurationSpellings(String duration) {
        var core = fixture(Document.CORE);
        ((ObjectNode) core.get("crypto")).put("ticket-ttl", duration);
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "tomorrow",
                "P",
                "PT",
                "P1DT",
                "P1Y",
                "15",
                "15seconds",
                "500ms",
                "PT0.5S",
                "PT1.0000000001S",
                "15s\n"
            })
    void mainSchemaRejectsInvalidDurationSyntaxAndPrecisionWithoutJavaConfigurationChecks(
            String duration) {
        var core = fixture(Document.CORE);
        ((ObjectNode) core.get("crypto")).put("ticket-ttl", duration);
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
    }

    @Test
    void directorySchemaEnforcesEntryShapesAndExactNumericBounds() {
        for (String invalid :
                List.of(
                        SERVERS.replace("25565", "0"),
                        SERVERS.replace("25565", "65536"),
                        SERVERS.replace("localhost", "local host"),
                        SERVERS.replace(
                                "\"port\": 25565",
                                "\"port\": 25565, \"tags\": [\"public\", \"public\"]"),
                        SERVERS.replace(
                                "\"port\": 25565", "\"port\": 25565, \"tags\": [\"\u2003\"]"),
                        SERVERS.replace(
                                "\"port\": 25565",
                                "\"port\": 25565, \"metadata\": {\"region\": 1}"),
                        SERVERS.replace("\"port\": 25565", "\"port\": 25565, \"typo\": true"),
                        SERVERS.replace("backend", "bad id"))) {
            assertThat(contract(Document.SERVERS).validate(JSON.readTree(invalid))).isNotEmpty();
        }
        var servers = fixture(Document.SERVERS);
        ((ObjectNode) servers.at("/servers/backend"))
                .put("port", new BigDecimal("25565.0000000000000000001"));
        assertThat(contract(Document.SERVERS).validate(servers)).isNotEmpty();
        assertThat(contract(Document.SERVERS).validate(JSON.readTree("{\"servers\":{}}")))
                .isEmpty();
    }

    @Test
    void trustSchemaDefinesKeysAndEveryPolicyField() {
        for (String invalid :
                List.of(
                        TRUST.replace("[\"MOJANG\"]", "[]"),
                        TRUST.replace("MOJANG", "UNKNOWN"),
                        TRUST.replace("[\"mojang\"]", "[\"mojang\", \"mojang\"]"),
                        TRUST.replace("[\"mojang\"]", "[\"Mojang\"]"),
                        TRUST.replace("key-1", "bad key"),
                        TRUST.replace(
                                "\"kid\": \"key-1\"", "\"kid\": \"key-1\", \"enabled\": \"false\""),
                        TRUST.replace("\"keys\":", "\"typo\": true, \"keys\":"))) {
            assertThat(contract(Document.TRUSTED_ISSUERS).validate(JSON.readTree(invalid)))
                    .isNotEmpty();
        }
        var trust = fixture(Document.TRUSTED_ISSUERS);
        ((ObjectNode) trust.at("/trusted-issuers/gateway")).remove("allowed-targets");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isNotEmpty();
        assertThat(
                        contract(Document.TRUSTED_ISSUERS)
                                .validate(JSON.readTree("{\"trusted-issuers\":{}}")))
                .isEmpty();
    }

    private static JsonNode fixture(Document document) {
        return JSON.readTree(
                switch (document) {
                    case CORE -> CORE;
                    case SERVERS -> SERVERS;
                    case TRUSTED_ISSUERS -> TRUST;
                });
    }

    private static Schema contract(Document document) {
        return REGISTRY.getSchema(SchemaLocation.of("classpath:" + document.schemaResource()));
    }
}
