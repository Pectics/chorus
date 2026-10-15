package me.pectics.chorus.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkeletonSmokeTest {

    @Test
    void toolchainRunsOnJava21OrNewer() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }

    @Test
    void assertionsAreWiredUp() {
        assertThat("chorus").startsWith("cho").endsWith("rus");
    }
}
