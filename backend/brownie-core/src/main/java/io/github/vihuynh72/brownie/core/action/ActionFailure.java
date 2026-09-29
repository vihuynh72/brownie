package io.github.vihuynh72.brownie.core.action;

/** Why an action ended without the change it proposed, in words a page can turn into a sentence. */
public enum ActionFailure {
    /** The connection it was approved for was disconnected, or replaced by another. */
    CONNECTION_CHANGED,
    /** The document it came from was moved to the trash or deleted. */
    DOCUMENT_GONE,
    /** The document was edited after the approval, so the approved content is no longer its content. */
    DOCUMENT_CHANGED,
    /** The document was exported again after the approval. */
    EXPORT_CHANGED,
    /**
     * The Google Doc to add to is no longer one Brownie made for this person,
     * or since the addition was shown it was changed, shared or unshared, put
     * in the trash, or is gone.
     */
    TARGET_CHANGED,
    /** The stored file no longer has the bytes that were approved. */
    CONTENT_CHANGED,
    /** Google refused the request itself. */
    PROVIDER_REFUSED,
    /** The person's Google storage is full. */
    STORAGE_FULL,
    /** The organization managing the account does not allow this. */
    BLOCKED_BY_ORGANIZATION,
    /** Google says the permission does not cover it. */
    PERMISSION_REFUSED,
    /** What it was to change no longer exists at Google, or cannot be reached with this permission. */
    TARGET_UNAVAILABLE,
    /** A usage limit Google sets, which a short wait does not lift. */
    LIMIT_REACHED,
    /** It was made, and reading it back found something other than what was approved. */
    READBACK_MISMATCH
}
