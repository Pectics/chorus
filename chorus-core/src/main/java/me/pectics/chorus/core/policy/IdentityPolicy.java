package me.pectics.chorus.core.policy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import me.pectics.chorus.core.model.IdentityClaim;

/** STRICT_ONLINE_MATCH additionally requires the engine's independent authentication check. */
public enum IdentityPolicy implements IdentityResolutionPolicy {
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
