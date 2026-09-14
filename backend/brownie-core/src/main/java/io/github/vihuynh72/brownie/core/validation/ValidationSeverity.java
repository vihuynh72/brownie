package io.github.vihuynh72.brownie.core.validation;

/**
 * How a finding affects whether a revision may be exported. {@link
 * #BLOCKING} always refuses export; {@link #WARNING} is visible but never
 * silently escalated or downgraded by a caller; {@link #INFORMATIONAL}
 * describes an expected condition (for example content a repeated field
 * legitimately inserted) rather than a defect. There is no operator
 * override that turns a {@link #BLOCKING} finding into a passed check --
 * only a new revision that actually resolves it produces a new, clean
 * manifest.
 */
public enum ValidationSeverity {
    BLOCKING,
    WARNING,
    INFORMATIONAL
}
