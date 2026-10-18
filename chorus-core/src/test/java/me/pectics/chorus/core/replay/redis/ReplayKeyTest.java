/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/redis/ReplayKey.java
 * sync: Keep issuer and ticket ID pairs injective without delimiter assumptions.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplayKeyTest {
    @Test
    void lengthPrefixKeepsPairsInjectiveAcrossDelimiters() {
        assertThat(ReplayKey.encode("gateway", "ticket"))
                .isEqualTo("chorus:replay:7:gateway:ticket");
        assertThat(ReplayKey.encode("a:1", "x")).isNotEqualTo(ReplayKey.encode("a", "1:x"));
        assertThat(ReplayKey.encode("a", "1:x")).isEqualTo("chorus:replay:1:a:1:x");
        assertThat(ReplayKey.encode("网关", "ticket")).isEqualTo("chorus:replay:2:网关:ticket");
    }
}
