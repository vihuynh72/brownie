package io.github.vihuynh72.brownie.core.revision;

/**
 * Whether an automated change can affect one field/item. Every field
 * starts {@link #EDITABLE}; a dedicated route to actually set {@link
 * #PRESERVE_ON_REGENERATION} or {@link #EXPLICITLY_LOCKED} -- and to
 * enforce that a locked field rejects an automated regeneration -- is a
 * later, dedicated task's job. This dimension exists now as a real,
 * independently addressable column so that a lock, once set, has
 * somewhere durable to live.
 */
public enum LockState {
    EDITABLE,
    PRESERVE_ON_REGENERATION,
    EXPLICITLY_LOCKED
}
