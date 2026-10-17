/*
 * @context
 * refs: ARCHITECTURE.md, section 2.4
 * related: chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/chorus.schema.json
 * sync: Keep role operations aligned with schema requirements for signing and replay protection.
 * @endcontext
 */
package me.pectics.chorus.core.node;

/** Fixed sender and receiver operations for each deployment role. */
public enum ServerNodeRole {
    GATEWAY,
    BACKEND,
    PEER;

    public boolean sender() {
        return switch (this) {
            case GATEWAY, PEER -> true;
            case BACKEND -> false;
        };
    }

    public boolean receiver() {
        return switch (this) {
            case BACKEND, PEER -> true;
            case GATEWAY -> false;
        };
    }
}
