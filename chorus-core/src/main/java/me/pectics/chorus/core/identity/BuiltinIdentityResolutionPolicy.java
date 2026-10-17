/*
 * @context
 * refs: ARCHITECTURE.md, section 3.5
 * related: chorus-core/src/main/java/me/pectics/chorus/core/identity/IdentityResolutionPolicy.java;
 *          chorus-core/src/main/java/me/pectics/chorus/core/engine/DefaultChorusCoreEngine.java
 * sync: Keep built-in profile mappings separate from issuer authorization and engine-side identity checks.
 * @endcontext
 */
package me.pectics.chorus.core.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Built-in profile mappings; STRICT_ONLINE_MATCH also requires the engine's authentication check.
 */
public enum BuiltinIdentityResolutionPolicy implements IdentityResolutionPolicy {
    ADOPT_UPSTREAM,
    FORCE_OFFLINE_UUID,
    STRICT_ONLINE_MATCH;

    @Override
    public ResolvedPlayerProfile resolve(IdentityClaim identity) {
        UUID uuid =
                this == FORCE_OFFLINE_UUID
                        ? UUID.nameUUIDFromBytes(
                                ("OfflinePlayer:" + identity.username())
                                        .getBytes(StandardCharsets.UTF_8))
                        : identity.sourceUuid();
        return new ResolvedPlayerProfile(
                uuid, identity.username(), identity.properties(), identity);
    }
}
