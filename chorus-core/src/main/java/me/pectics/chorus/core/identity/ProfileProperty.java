package me.pectics.chorus.core.identity;

import java.util.Objects;

/**
 * An upstream textures property, not an independent Mojang attestation. Value and any present
 * signature must be nonempty; signature may be null.
 */
public record ProfileProperty(String name, String value, String signature) {
    public ProfileProperty {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!name.equals("textures")
                || value.isEmpty()
                || signature != null && signature.isEmpty()) {
            throw new IllegalArgumentException("Only nonempty textures properties are supported");
        }
    }
}
