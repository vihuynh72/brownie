package io.github.vihuynh72.brownie.core.retention;

/**
 * How far one deletion request has got. {@code TRASHED} is the only state a
 * person can still change their mind from. {@code PURGED} means every
 * database row is gone and access has ended, but stored objects may still
 * be waiting for the worker to remove them; {@code VERIFIED} means a
 * recount found no row and no waiting object left.
 */
public enum DeletionState {
    TRASHED,
    RESTORED,
    PURGED,
    VERIFIED;

    /** True once the request can no longer be restored. */
    public boolean isPermanent() {
        return this == PURGED || this == VERIFIED;
    }
}
