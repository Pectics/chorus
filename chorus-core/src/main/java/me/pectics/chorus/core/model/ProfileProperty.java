package me.pectics.chorus.core.model;

import java.util.Objects;

/**
 * An upstream property, not an independently verified Mojang attestation. Signature may be null.
 */
public record ProfileProperty(String name, String value, String signature) {
    public ProfileProperty {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!name.equals("textures") || value.isEmpty()) {
            throw new IllegalArgumentException("Only nonempty textures properties are supported");
        }
    }
}
