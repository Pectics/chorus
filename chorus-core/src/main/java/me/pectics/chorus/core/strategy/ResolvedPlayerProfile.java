package me.pectics.chorus.core.strategy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import me.pectics.chorus.core.model.IdentityClaim;
import me.pectics.chorus.core.model.ProfileProperty;

/** Target profile plus the unchanged source identity for subsequent transfers. */
public record ResolvedPlayerProfile(
        UUID finalUuid,
        String finalUsername,
        List<ProfileProperty> properties,
        IdentityClaim originalIdentity) {
    public ResolvedPlayerProfile {
        Objects.requireNonNull(finalUuid, "finalUuid");
        Objects.requireNonNull(finalUsername, "finalUsername");
        Objects.requireNonNull(originalIdentity, "originalIdentity");
        properties = List.copyOf(properties);
    }
}
