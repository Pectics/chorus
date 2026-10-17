package me.pectics.chorus.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader.ConfigurationDocumentType;
import me.pectics.chorus.core.crypto.TicketCryptoConfiguration;
import me.pectics.chorus.core.crypto.config.TicketCryptoConfigurationLoader;
import me.pectics.chorus.core.registry.config.RegistryConfigurationLoader;
import me.pectics.chorus.core.replay.config.ReplayGuardConfigurationLoader;
import me.pectics.chorus.core.ticket.TicketCompression;
import me.pectics.chorus.core.ticket.config.TicketConfigurationLoader;
import me.pectics.chorus.core.ticket.config.TicketIssuanceConfigurationLoader;
import me.pectics.chorus.core.ticket.config.TicketVerificationConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class BusinessConfigurationLoaderTest {
    @TempDir Path directory;

    @Test
    void typedConversionResolvesPathsAndDurationsWithoutCreatingKeysOrReplayState()
            throws Exception {
        var json = JsonMapper.shared();
        var source = (ObjectNode) json.readTree(ConfigurationSchemaTest.CORE);
        ((ObjectNode) source.get("node")).put("role", "PEER");
        ((ObjectNode) source.get("crypto"))
                .put("algorithm", "HMAC_SHA256")
                .put("key", "keys/shared.key");
        ((ObjectNode) source.get("ticket")).remove("compression");
        source.set(
                "issuance",
                json.valueToTree(
                        Map.of(
                                "ttl", "12000ms",
                                "allowed-auth-schemes", new String[] {"MOJANG"},
                                "allowed-providers", new String[] {"mojang"},
                                "allowed-targets", new String[] {"backend"})));
        ((ObjectNode) source.get("verification")).put("max-lifetime", "PT20S");
        Path file =
                Files.writeString(directory.resolve("chorus.yml"), json.writeValueAsString(source));
        var root = new YamlConfigurationLoader(ConfigurationDocumentType.CORE).load(file);
        var crypto =
                (TicketCryptoConfiguration.HmacSha256)
                        new TicketCryptoConfigurationLoader().load(root.get("crypto"), directory);
        var registry = new RegistryConfigurationLoader().load(root.get("registry"), directory);
        var replay = new ReplayGuardConfigurationLoader().load(root.get("replay-guard"), directory);
        var issuance =
                new TicketIssuanceConfigurationLoader().load(root.get("issuance"), directory);
        var verification =
                new TicketVerificationConfigurationLoader()
                        .load(root.get("verification"), directory);
        assertThat(crypto.key()).isEqualTo(directory.resolve("keys/shared.key"));
        assertThat(registry.config().serversPath()).isEqualTo(directory.resolve("servers.yml"));
        assertThat(replay.local().path()).isEqualTo(directory.resolve("state/replay.db"));
        assertThat(replay.local().busyTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(issuance.ttl()).isEqualTo(Duration.ofSeconds(12));
        assertThat(verification.maxLifetime()).isEqualTo(Duration.ofSeconds(20));
        assertThat(verification.trustedIssuersPath())
                .isEqualTo(directory.resolve("trusted-issuers.yml"));
        assertThat(
                        new TicketConfigurationLoader()
                                .load(root.get("ticket"), directory)
                                .compression())
                .isEqualTo(TicketCompression.ZSTD);
        assertThat(crypto.key()).doesNotExist();
        assertThat(replay.local().path().getParent()).doesNotExist();
        assertThat(registry.config().serversPath()).doesNotExist();
    }

    @Test
    void receiverCanDescribeItsAlgorithmWithoutLocalSigningPaths() throws Exception {
        Path file =
                Files.writeString(directory.resolve("chorus.yml"), ConfigurationSchemaTest.CORE);
        var root = new YamlConfigurationLoader(ConfigurationDocumentType.CORE).load(file);
        assertThat(new TicketCryptoConfigurationLoader().load(root.get("crypto"), directory))
                .isEqualTo(new TicketCryptoConfiguration.Ed25519(null, null));
    }
}
