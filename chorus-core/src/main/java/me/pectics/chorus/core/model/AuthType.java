package me.pectics.chorus.core.model;

/** Authentication provenance; authorization is decided by the receiver, not this enum. */
public enum AuthType {
    MOJANG,
    OFFLINE_AUTH,
    OFFLINE_RAW,
    YGGDRASIL,
    FLOODGATE
}
