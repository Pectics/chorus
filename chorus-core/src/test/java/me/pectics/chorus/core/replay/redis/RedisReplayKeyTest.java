/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/redis/RedisReplayKey.java
 * sync: Keep issuer and ticket ID pairs injective without delimiter assumptions.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedisReplayKeyTest {
    @Test
    void lengthPrefixKeepsPairsInjectiveAcrossDelimiters() {
        assertThat(RedisReplayKey.encode("gateway", "ticket"))
                .isEqualTo("chorus:replay:7:gateway:ticket");
        assertThat(RedisReplayKey.encode("a:1", "x"))
                .isNotEqualTo(RedisReplayKey.encode("a", "1:x"));
        assertThat(RedisReplayKey.encode("a", "1:x")).isEqualTo("chorus:replay:1:a:1:x");
        assertThat(RedisReplayKey.encode("网关", "ticket")).isEqualTo("chorus:replay:2:网关:ticket");
    }
}
