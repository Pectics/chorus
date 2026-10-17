package me.pectics.chorus.core.identity;

/** Authentication provenance; authorization is decided by the receiver, not this enum. */
public enum AuthenticationSourceType {
    MOJANG,
    OFFLINE_AUTH,
    OFFLINE_RAW,
    YGGDRASIL,
    FLOODGATE
}
