package me.pectics.chorus.core.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable source identity. UUID mapping must never replace this claim on a later transfer. */
public record IdentityClaim(
        String username,
        AuthType authType,
        String authProvider,
        String providerSubject,
        UUID sourceUuid,
        List<ProfileProperty> properties) {
    public IdentityClaim {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(authType, "authType");
        Objects.requireNonNull(authProvider, "authProvider");
        Objects.requireNonNull(providerSubject, "providerSubject");
        Objects.requireNonNull(sourceUuid, "sourceUuid");
        properties = List.copyOf(properties);
        if (!username.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IllegalArgumentException("Invalid Java Edition username");
        }
        if (!authProvider.matches("[a-z0-9][a-z0-9._/-]{0,127}")
                || providerSubject.isBlank()
                || providerSubject.length() > 256) {
            throw new IllegalArgumentException("Invalid authentication provider or subject");
        }
        if (properties.size() > 1) {
            throw new IllegalArgumentException("At most one textures property is supported");
        }
    }
}
