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
import me.pectics.chorus.core.config.model.NodeRole;
import me.pectics.chorus.core.crypto.SignatureAlgorithm;
import me.pectics.chorus.core.crypto.TicketCompression;
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
              "node": {"id": "backend", "role": "BACKEND"},
              "ticket": {"cookie-key": "chorus:ticket", "max-payload-bytes": 4096,
                "max-property-bytes": 2048, "compression": "NONE"},
              "crypto": {"algorithm": "ED25519"},
              "registry": {"provider": "CONFIG", "config": {"servers-path": "servers.yml", "issuers-path": "trusted-issuers.yml"}},
              "verification": {"max-lifetime": "15s", "clock-skew": "2s",
                "enforce-transfer-intent": true, "identity-policy": "ADOPT_UPSTREAM"},
              "replay-guard": {"backend": "LOCAL", "local": {"path": "state/replay.db", "max-entries": 100, "busy-timeout": "500ms"}}
            }
            """;
    static final String SERVERS =
            """
            {"backend": {"address": "localhost:25565"}}
            """;
    static final String TRUST =
            """
            {"gateway": {
              "keys": ["gateway.pub"],
              "allowed-auth-schemes": ["MOJANG"], "allowed-providers": ["mojang"]
            }}
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
            assertThat(schema.has("properties") || schema.get("additionalProperties").isObject())
                    .isTrue();
            assertThat(schema.has("$ref")).isFalse();
            assertThat(contract(document).validate(fixture(document))).isEmpty();
            for (var other : Document.values()) {
                if (other != document) {
                    assertThat(contract(document).validate(fixture(other))).isNotEmpty();
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(NodeRole.class)
    void rolesControlRequiredSectionsWithinTheMainSchema(NodeRole role) {
        var core = (ObjectNode) fixture(Document.CORE);
        ((ObjectNode) core.get("node")).put("role", role.name());
        assertThat(contract(Document.CORE).validate(core).isEmpty())
                .isEqualTo(role == NodeRole.BACKEND);
        ((ObjectNode) core.get("crypto"))
                .put("private-key", "private.der")
                .put("public-key", "public.der");
        var issuance = ((ObjectNode) fixture(Document.TRUSTED_ISSUERS).at("/gateway"));
        issuance.remove("keys");
        issuance.put("ttl", "15s");
        issuance.putArray("allowed-targets").add("backend");
        core.set("issuance", issuance);
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
        var withoutReplay = core.deepCopy();
        withoutReplay.remove("replay-guard");
        assertThat(contract(Document.CORE).validate(withoutReplay).isEmpty())
                .isEqualTo(role == NodeRole.GATEWAY);
        var withoutIssuance = core.deepCopy();
        withoutIssuance.remove("issuance");
        assertThat(contract(Document.CORE).validate(withoutIssuance).isEmpty())
                .isEqualTo(role == NodeRole.BACKEND);
        for (String field : List.of("private-key", "public-key")) {
            var withoutKey = core.deepCopy();
            ((ObjectNode) withoutKey.get("crypto")).remove(field);
            assertThat(contract(Document.CORE).validate(withoutKey).isEmpty())
                    .isEqualTo(role == NodeRole.BACKEND);
        }
    }

    @ParameterizedTest
    @EnumSource(NodeRole.class)
    void hmacSendersRequireOneSharedKeyWithoutPublicOrPrivateKeyFields(NodeRole role) {
        var core = (ObjectNode) fixture(Document.CORE);
        ((ObjectNode) core.get("node")).put("role", role.name());
        var crypto = (ObjectNode) core.get("crypto");
        crypto.put("algorithm", "HMAC_SHA256").put("key", "keys/shared.key");
        var issuance = (ObjectNode) fixture(Document.TRUSTED_ISSUERS).at("/gateway");
        issuance.remove("keys");
        issuance.put("ttl", "15s");
        issuance.putArray("allowed-targets").add("backend");
        core.set("issuance", issuance);
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
        crypto.remove("key");
        assertThat(contract(Document.CORE).validate(core).isEmpty())
                .isEqualTo(role == NodeRole.BACKEND);
        crypto.put("key", "keys/shared.key");
        for (String field : List.of("private-key", "public-key")) {
            crypto.put(field, "legacy.der");
            assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
            crypto.remove(field);
        }
        crypto.put("algorithm", "ED25519");
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(SignatureAlgorithm.class)
    void trustSchemaAcceptsExplicitSupportedAlgorithmsAndRejectsUnknownOnes(
            SignatureAlgorithm algorithm) {
        var trust = fixture(Document.TRUSTED_ISSUERS);
        var issuer = (ObjectNode) trust.get("gateway");
        issuer.put("algorithm", algorithm.name());
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isEmpty();
        issuer.put("algorithm", "HS512");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isNotEmpty();
    }

    @Test
    void mainSchemaDefinesRequiredFieldsTypesLimitsAndImplementedOptions() {
        for (String invalid :
                List.of(
                        CORE.replace("\"id\": \"backend\"", "\"id\": \"bad id\""),
                        CORE.replace("\"role\": \"BACKEND\"", "\"role\": false"),
                        CORE.replace("BACKEND", "UNKNOWN"),
                        CORE.replace(
                                "\"role\": \"BACKEND\"",
                                "\"role\": \"BACKEND\", \"capabilities\": {\"sender\": true, \"receiver\": true}"),
                        CORE.replace("\"algorithm\": \"ED25519\"", "\"algorithm\": \"HMAC\""),
                        CORE.replace(
                                "\"algorithm\": \"ED25519\"",
                                "\"algorithm\": \"ED25519\", \"compress-payload\": true"),
                        CORE.replace("4096", "5121"),
                        CORE.replace("2048", "0"),
                        CORE.replace("\"max-entries\": 100", "\"max-entries\": 2147483648"),
                        CORE.replace("CONFIG", "SHARED_SQL"),
                        CORE.replace("CONFIG", "STATIC"),
                        CORE.replace("\"config\":", "\"static\":"),
                        CORE.replace("LOCAL", "UNKNOWN"),
                        CORE.replace("NONE", "UNKNOWN"),
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
        core = (ObjectNode) fixture(Document.CORE);
        ((ObjectNode) core.get("node")).remove("role");
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
        ((ObjectNode) core.get("verification")).put("max-lifetime", duration);
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
        ((ObjectNode) core.get("verification")).put("max-lifetime", duration);
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
    }

    @Test
    void directorySchemaEnforcesFlatEntriesAndAddressBounds() {
        for (String invalid :
                List.of(
                        SERVERS.replace("25565", "0"),
                        SERVERS.replace("25565", "65536"),
                        SERVERS.replace("25565", "25565.0"),
                        SERVERS.replace("localhost", "local host"),
                        SERVERS.replace("localhost", ""),
                        SERVERS.replace("localhost:25565", "::1:25565"),
                        SERVERS.replace("localhost:25565", "[::1:25565"),
                        SERVERS.replace("localhost:25565", "localhost"),
                        SERVERS.replace("backend", "bad id"),
                        "{\"servers\":" + SERVERS + "}",
                        "{\"backend\":{\"host\":\"localhost\",\"port\":25565}}")) {
            assertThat(contract(Document.SERVERS).validate(JSON.readTree(invalid))).isNotEmpty();
        }
        assertThat(contract(Document.SERVERS).validate(JSON.readTree("{}"))).isEmpty();
        var core = fixture(Document.CORE);
        ((ObjectNode) core.get("ticket"))
                .put("max-payload-bytes", new BigDecimal("4096.0000000000000000001"));
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost:1",
                "mc.example.net:65535",
                "127.0.0.1:25565",
                "[::1]:25565",
                "[2001:db8::1]:25565",
                "[fe80::1%eth0]:25565"
            })
    void directorySchemaAcceptsExplicitHostnameIpv4AndBracketedIpv6Addresses(String address) {
        var servers = fixture(Document.SERVERS);
        ((ObjectNode) servers.get("backend")).put("address", address);
        assertThat(contract(Document.SERVERS).validate(servers)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"tags", "metadata"})
    void directorySchemaRejectsRemovedExtensionFields(String field) {
        var servers = fixture(Document.SERVERS);
        var entry = (ObjectNode) servers.at("/backend");
        if (field.equals("tags")) {
            entry.putArray(field).add("lobby");
        } else {
            entry.putObject(field).put("region", "cn");
        }
        assertThat(contract(Document.SERVERS).validate(servers))
                .anySatisfy(
                        error -> {
                            assertThat(error.getKeyword()).isEqualTo("additionalProperties");
                            assertThat(error.getInstanceLocation().toString())
                                    .isEqualTo("/backend");
                        });
    }

    @Test
    void trustSchemaDefinesKeysAndEveryPolicyField() {
        for (String invalid :
                List.of(
                        TRUST.replace("[\"MOJANG\"]", "[]"),
                        TRUST.replace("MOJANG", "UNKNOWN"),
                        TRUST.replace("[\"mojang\"]", "[\"mojang\", \"mojang\"]"),
                        TRUST.replace("[\"mojang\"]", "[\"Mojang\"]"),
                        TRUST.replace("[\"gateway.pub\"]", "[]"),
                        TRUST.replace("[\"gateway.pub\"]", "[\" \u2003\"]"),
                        TRUST.replace("[\"gateway.pub\"]", "[null]"),
                        TRUST.replace("[\"gateway.pub\"]", "[false]"),
                        TRUST.replace("[\"gateway.pub\"]", "[\"gateway.pub\", \"gateway.pub\"]"),
                        TRUST.replace(
                                "[\"gateway.pub\"]",
                                "[{\"public-key-file\": \"gateway.pub\", \"enabled\": true}]"),
                        TRUST.replace("\"keys\":", "\"enabled\": \"false\", \"keys\":"),
                        TRUST.replace("\"keys\":", "\"allowed-targets\": [\"backend\"], \"keys\":"),
                        TRUST.replace("\"keys\":", "\"typo\": true, \"keys\":"))) {
            assertThat(contract(Document.TRUSTED_ISSUERS).validate(JSON.readTree(invalid)))
                    .isNotEmpty();
        }
        var trust = fixture(Document.TRUSTED_ISSUERS);
        ((ObjectNode) trust.at("/gateway")).remove("allowed-providers");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isNotEmpty();
        trust = fixture(Document.TRUSTED_ISSUERS);
        ((ObjectNode) trust.at("/gateway")).put("enabled", false);
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isEmpty();
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(JSON.readTree("{}"))).isEmpty();
    }

    @Test
    void trustSchemaAcceptsEitherKeyFormOrBothAndRequiresAtLeastOne() {
        var trust = fixture(Document.TRUSTED_ISSUERS);
        var issuer = (ObjectNode) trust.at("/gateway");
        issuer.put("key", "gateway.pub");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isEmpty();
        issuer.remove("keys");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isEmpty();
        issuer.remove("key");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isNotEmpty();
    }

    @Test
    void trustSchemaValidatesEachPresentKeyFieldWithoutFallingBackFromInvalidKeys() {
        for (String invalid : List.of("null", "false", "42", "[]", "{}", "\"\"", "\" \u2003\"")) {
            var trust = fixture(Document.TRUSTED_ISSUERS);
            var issuer = (ObjectNode) trust.at("/gateway");
            issuer.set("key", JSON.readTree(invalid));
            assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isNotEmpty();
            issuer.remove("keys");
            assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust)).isNotEmpty();
        }
        var trust = fixture(Document.TRUSTED_ISSUERS);
        var issuer = (ObjectNode) trust.at("/gateway");
        issuer.put("key", "gateway.pub");
        issuer.putArray("keys");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust))
                .anySatisfy(error -> assertThat(error.getKeyword()).isEqualTo("minItems"));
    }

    @ParameterizedTest
    @EnumSource(TicketCompression.class)
    void schemaAcceptsEveryImplementedCompression(TicketCompression compression) {
        var core = fixture(Document.CORE);
        ((ObjectNode) core.get("ticket")).put("compression", compression.name());
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
    }

    @Test
    void schemaMakesCompressionOptionalAndDocumentsZstdAsTheDefault() throws Exception {
        var core = fixture(Document.CORE);
        ((ObjectNode) core.get("ticket")).remove("compression");
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
        try (var input = getClass().getResourceAsStream(Document.CORE.schemaResource())) {
            assertThat(
                            JSON.readTree(input)
                                    .at("/properties/ticket/properties/compression/default")
                                    .asString())
                    .isEqualTo(TicketCompression.ZSTD.name());
        }
        for (String invalid :
                List.of("null", "false", "42", "[]", "{}", "\"zstd\"", "\"BROTLI\"", "\"\"")) {
            ((ObjectNode) core.get("ticket")).set("compression", JSON.readTree(invalid));
            assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
        }
    }

    @Test
    void schemaRecordsReservedReplayBackendsAndRejectsRegistryReplayWithConfigFiles() {
        var core = fixture(Document.CORE);
        var replay = (ObjectNode) core.get("replay-guard");
        replay.put("backend", "REDIS");
        replay.putObject("redis");
        assertThat(contract(Document.CORE).validate(core)).isEmpty();
        replay.put("backend", "REGISTRY");
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
        replay.put("backend", "LOCAL");
        replay.remove("local");
        assertThat(contract(Document.CORE).validate(core)).isNotEmpty();
    }

    @Test
    void schemasRejectLegacyRootWrappersAndRenamedPermissions() {
        assertThat(
                        contract(Document.TRUSTED_ISSUERS)
                                .validate(JSON.readTree("{\"trusted-issuers\":" + TRUST + "}")))
                .isNotEmpty();
        assertThat(
                        contract(Document.TRUSTED_ISSUERS)
                                .validate(
                                        JSON.readTree(
                                                TRUST.replace(
                                                        "allowed-auth-schemes",
                                                        "allowed-auth-types"))))
                .isNotEmpty();
        assertThat(
                        contract(Document.CORE)
                                .validate(
                                        JSON.readTree(
                                                CORE.replace(
                                                        "enforce-transfer-intent",
                                                        "require-transfer-intent"))))
                .isNotEmpty();
    }

    @Test
    void schemasRejectRemovedKeyIdentifiers() {
        var core = fixture(Document.CORE);
        ((ObjectNode) core.get("crypto")).put("key-id", "removed");
        assertThat(contract(Document.CORE).validate(core))
                .anySatisfy(
                        error -> assertThat(error.getKeyword()).isEqualTo("additionalProperties"));
        var trust = fixture(Document.TRUSTED_ISSUERS);
        ((ObjectNode) trust.at("/gateway"))
                .putArray("keys")
                .addObject()
                .put("public-key", "gateway.pub")
                .put("kid", "removed");
        assertThat(contract(Document.TRUSTED_ISSUERS).validate(trust))
                .anySatisfy(error -> assertThat(error.getKeyword()).isEqualTo("type"));
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
