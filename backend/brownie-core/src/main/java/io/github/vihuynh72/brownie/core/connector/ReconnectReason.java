package io.github.vihuynh72.brownie.core.connector;

/** Why a connection needs its person to connect again. */
public enum ReconnectReason {
    /**
     * The provider refused the token: the person removed Brownie's access,
     * the token was unused for too long, or the provider limits how long a
     * token lasts for an app it treats as still being tested.
     */
    TOKEN_REJECTED,
    /** The stored token can no longer be decrypted, usually because the key it was encrypted with has been changed. */
    TOKEN_UNREADABLE,
    /** The provider still answers, but no longer grants the permission this kind of access needs. */
    PERMISSION_MISSING
}
