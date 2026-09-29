package io.github.vihuynh72.brownie.core.action;

/**
 * What reading a change back found, which is what "it worked" is taken to
 * mean: a provider accepting a request is not the same as the change being
 * what was approved.
 */
public enum ActionVerification {
    /** Every field that was approved reads back as approved; for a file, its bytes by their checksum. */
    MATCHED,
    /** Google converted the file, and every filled-in value was found in the result. A conversion is never called identical. */
    CONVERSION_CHECKED,
    /** Google converted the file, and some filled-in values were not found in the result; the person is told how many. */
    CONVERSION_DIFFERS,
    /**
     * Google converted the file, and no filled-in value was long enough to
     * look for, so nothing of its content was checked; only that it is a
     * Google Doc where it should be. Never called checked.
     */
    CONVERSION_UNCHECKED,
    /** It was made as approved and has since been deleted in the person's account. */
    REMOVED_AFTERWARDS,
    /** Something was made, and it is not what was approved. */
    MISMATCHED
}
