package me.pectics.chorus.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.pectics.chorus.core.config.ConfigurationLoader.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigurationLoaderTest {
    @TempDir Path directory;

    @Test
    void reportsFileAndFieldWithoutEchoingRejectedValues() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("chorus.yml"),
                        ConfigurationSchemaTest.CORE.replace("ED25519", "secret-value"));
        assertThatThrownBy(() -> ConfigurationLoader.read(file, Document.CORE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("/crypto/algorithm (const)")
                .hasMessageNotContaining("secret-value");
    }

    @Test
    void convertsDecimalScalarsWithoutRoundingAwayFractions() throws Exception {
        Path file =
                Files.writeString(
                        directory.resolve("servers.yml"),
                        "servers: {backend: {host: localhost, port: 25565.0}}");
        assertThat(
                        ConfigurationLoader.read(file, Document.SERVERS)
                                .at("/servers/backend/port")
                                .asInt())
                .isEqualTo(25565);
        Files.writeString(
                file, "servers: {backend: {host: localhost, port: 25565.0000000000000000001}}");
        assertThatThrownBy(() -> ConfigurationLoader.read(file, Document.SERVERS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/servers/backend/port (type)");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "servers: {}\nservers: {}",
                "servers: !!java.util.HashMap {}",
                "servers: {a: &node {host: localhost, port: 25565}, b: *node}",
                "servers: {backend: {host: localhost, port: 25565, metadata: {1: value}}}",
                "servers: {backend: {host: localhost, port: 25565, metadata: {date: 2026-10-16}}}",
                "servers: {backend: {host: localhost, port: .nan}}",
                "servers: {backend: {host: !!binary bG9jYWxob3N0, port: 25565}}",
                "servers: {backend: {host: localhost, port: 25565, tags: !!set {tag: null}}}",
                "servers: {}\n---\nservers: {}"
            })
    void rejectsUnsafeYamlAndNonJsonTypes(String yaml) throws Exception {
        Path file = Files.writeString(directory.resolve("servers.yml"), yaml);
        assertThatThrownBy(() -> ConfigurationLoader.read(file, Document.SERVERS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesByteDepthAndUtf8Limits() throws Exception {
        Path file = directory.resolve("chorus.yml");
        Files.writeString(file, "#" + "x".repeat(1_048_576));
        assertThatThrownBy(() -> ConfigurationLoader.read(file, Document.CORE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size limit");
        Files.writeString(file, "[".repeat(15) + "]".repeat(15));
        assertThatThrownBy(() -> ConfigurationLoader.read(file, Document.CORE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid YAML");
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        assertThatThrownBy(() -> ConfigurationLoader.read(file, Document.CORE))
                .isInstanceOf(CharacterCodingException.class);
    }
}
