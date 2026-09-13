package io.github.vihuynh72.brownie.core.revision;

/**
 * A person's own decision about one field/item for one revision. Every
 * field starts {@link #UNREVIEWED} today; a dedicated route to actually
 * record an explicit {@link #ACCEPTED}, {@link #REJECTED}, or {@link
 * #NEEDS_CLARIFICATION} decision -- with its own actor and reason -- and
 * to invalidate it back to {@code UNREVIEWED} after a relevant edit, is a
 * later, dedicated task's job. This dimension exists now as a real,
 * independently addressable column so that work has something to write
 * to, tracked apart from authorship or evidence since a person can accept
 * a directly-supported, AI-composed value or reject a directly-supported,
 * user-authored one -- the two are genuinely independent.
 */
public enum ReviewState {
    UNREVIEWED,
    ACCEPTED,
    REJECTED,
    NEEDS_CLARIFICATION
}
