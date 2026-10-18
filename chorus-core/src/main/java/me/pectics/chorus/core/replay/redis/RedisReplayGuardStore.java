/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * related: chorus-core/src/main/java/me/pectics/chorus/core/replay/redis/RedisReplayGuard.java
 * sync: Register with the worker's remaining TTL and never retry unknown command outcomes.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

import me.pectics.chorus.core.replay.ReplayGuardStore;

/**
 * Synchronous Redis storage; operations may run concurrently, and close follows their completion.
 */
public interface RedisReplayGuardStore extends ReplayGuardStore, AutoCloseable {
    /**
     * Registers a nonblank issuer/ticket pair with a positive remaining TTL in milliseconds.
     * Returns after acknowledgement; durability of registration and consumption depends on Redis
     * server persistence settings. Duplicate registrations or unknown outcomes throw a runtime
     * exception.
     */
    void register(String issuerNodeId, String jti, long ttlMillis);

    /**
     * Releases the client after all operations end; close may block or throw a runtime exception.
     */
    @Override
    void close();
}
