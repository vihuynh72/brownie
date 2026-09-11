package io.github.vihuynh72.brownie.core.template;

public enum UnsupportedBindingReason {
    /** No node in the pinned extraction graph matches this binding target at all. */
    NOT_FOUND,
    /** More than one node matches -- the target does not uniquely identify a location, so which one is meant is not determined. */
    AMBIGUOUS,
    /** Another field definition in the same request already uses this exact {@code fieldId}. */
    DUPLICATE_FIELD_ID
}
