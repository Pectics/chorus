/*
 * @context
 * refs: ARCHITECTURE.md, section 3.6
 * sync: Keep issuer and ticket IDs injective without parsing keys.
 * @endcontext
 */
package me.pectics.chorus.core.replay.redis;

/** Length-prefixed key encoding; the key is compared, never parsed. */
final class ReplayKey {
    private ReplayKey() {}

    static String encode(String issuerNodeId, String jti) {
        return "chorus:replay:" + issuerNodeId.length() + ":" + issuerNodeId + ":" + jti;
    }
}
