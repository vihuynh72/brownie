package io.github.vihuynh72.brownie.core.revision;

/**
 * What {@link PatchComparator} found for one field a proposal touches.
 * {@link #CLEAN} is the only status {@link RevisionService#acceptPatch}
 * ever applies -- a simultaneous edit ({@link #CONFLICT}) or a locked
 * field ({@link #LOCKED}) always produces a reviewable, visible outcome
 * instead of a silent overwrite, never last-write-wins data loss.
 */
public enum PatchFieldStatus {
    CLEAN,
    CONFLICT,
    LOCKED
}
