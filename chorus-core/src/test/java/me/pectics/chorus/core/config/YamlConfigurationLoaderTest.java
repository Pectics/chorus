package me.pectics.chorus.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader;
import me.pectics.chorus.core.config.yaml.YamlConfigurationLoader.ConfigurationDocumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YamlConfigurationLoaderTest {
    @TempDir Path directory;

    @Test
    void reportsFileAndFieldWithoutEchoingRejectedValues() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("chorus.yml"),
                        ConfigurationSchemaTest.CORE.replace("ED25519", "secret-value"));
        assertThatThrownBy(
                        () ->
                                new YamlConfigurationLoader(ConfigurationDocumentType.CORE)
                                        .load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("/crypto/algorithm (enum)")
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void convertsDecimalScalarsWithoutRoundingAwayFractions() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("servers.yml"),
                        ConfigurationSchemaTest.CORE.replace("4096", "4096.0"));
        assertThat(
                        new YamlConfigurationLoader(ConfigurationDocumentType.CORE)
                                .load(file)
                                .at("/ticket/max-payload-bytes")
                                .asInt())
                .isEqualTo(4096);
        Files.writeString(
                file, ConfigurationSchemaTest.CORE.replace("4096", "4096.0000000000000000001"));
        assertThatThrownBy(
                        () ->
                                new YamlConfigurationLoader(ConfigurationDocumentType.CORE)
                                        .load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/ticket/max-payload-bytes (type)");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "backend: {}\nbackend: {}",
                "!!java.util.HashMap {}",
                "a: &node {address: localhost:25565}\nb: *node",
                "1: {address: localhost:25565}",
                "backend: {address: 2026-10-16}",
                "backend: {address: .nan}",
                "backend: {address: !!binary bG9jYWxob3N0}",
                "!!set {backend: null}",
                "{}\n---\n{}"
            })
    void rejectsUnsafeYamlAndNonJsonTypes(String yaml) throws Exception {
        Path file = Files.writeString(directory.resolve("servers.yml"), yaml);
        assertThatThrownBy(
                        () ->
                                new YamlConfigurationLoader(ConfigurationDocumentType.SERVERS)
                                        .load(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesByteDepthAndUtf8Limits() throws Exception {
        Path file = directory.resolve("chorus.yml");
        Files.writeString(file, "#" + "x".repeat(1_048_576));
        assertThatThrownBy(
                        () ->
                                new YamlConfigurationLoader(ConfigurationDocumentType.CORE)
                                        .load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
        Files.writeString(file, "[".repeat(15) + "]".repeat(15));
        assertThatThrownBy(
                        () ->
                                new YamlConfigurationLoader(ConfigurationDocumentType.CORE)
                                        .load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid YAML");
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(
                        () ->
                                new YamlConfigurationLoader(ConfigurationDocumentType.CORE)
                                        .load(file))
                .isInstanceOf(CharacterCodingException.class);
    }
}
