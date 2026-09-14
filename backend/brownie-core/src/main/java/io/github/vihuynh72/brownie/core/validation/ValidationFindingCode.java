package io.github.vihuynh72.brownie.core.validation;

/**
 * A stable, machine-readable identity for one kind of validation finding --
 * a caller resolves or filters on this code, never on {@link
 * ValidationFinding#message()}'s free text, which may be reworded without
 * that being a breaking change. Each code's own fixed {@link
 * #severity()} is a property of the code itself, the same "not a
 * caller-choosable field" discipline {@link
 * io.github.vihuynh72.brownie.core.rule.RulePayload#category()} already
 * uses -- an operator cannot downgrade a blocking code to a warning by
 * constructing the finding differently.
 */
public enum ValidationFindingCode {

    /** A field the template or an accepted rule requires has no value in this revision's content. */
    MISSING_REQUIRED_FIELD(ValidationSeverity.BLOCKING),

    /** An accepted {@code MaxTextLength} rule's bound field currently holds more characters than the rule allows. */
    TEXT_LENGTH_EXCEEDED(ValidationSeverity.BLOCKING),

    /** An accepted {@code MaxItemCount} rule's bound repeated field currently holds more items than the rule allows. */
    ITEM_COUNT_EXCEEDED(ValidationSeverity.BLOCKING),

    /** A field's own intended text did not survive into the freshly filled DOCX's reopened body text. */
    FIELD_CONTENT_NOT_IN_OUTPUT(ValidationSeverity.BLOCKING),

    /** Re-extracting the freshly filled DOCX reported an unsupported feature the fill pass itself introduced. */
    PACKAGE_INTEGRITY_FAILURE(ValidationSeverity.BLOCKING),

    /** A source span this revision cites as evidence no longer resolves for this workspace. */
    INVALID_EVIDENCE_REFERENCE(ValidationSeverity.BLOCKING),

    /** An accepted {@code ProtectedRegion} rule's target text or style differs between the template's own source and the filled output. */
    PROTECTED_REGION_MODIFIED(ValidationSeverity.BLOCKING),

    /** Protected (non-field-bound) content differs, in sequence, text, or style, between the template's own qualified baseline render and the filled output -- a change the template's own approved rules never explicitly named, caught instead by the broader whole-document comparison. */
    LAYOUT_PROTECTED_REGION_CHANGE(ValidationSeverity.BLOCKING),

    /** Content was added or removed relative to the qualified baseline in a way this check cannot precisely align (for example an empty-repeated-group explanatory line the baseline's own non-empty sample never triggers) -- named rather than silently ignored, but not itself a proven defect. */
    LAYOUT_EXPECTED_INSERTION(ValidationSeverity.INFORMATIONAL),

    /** The rendered page count differs between the qualified baseline and the filled output -- expected under the default flowing layout, so informational rather than a defect on its own. */
    LAYOUT_PAGE_COUNT_CHANGED(ValidationSeverity.INFORMATIONAL),

    /** A coarse whole-page rasterized comparison found a page whose pixel difference from the qualified baseline exceeds this check's own threshold -- a real signal worth a person's attention, but too coarse (anti-aliasing, expected reflow) to treat as a proven defect on its own. */
    LAYOUT_VISUAL_DIFFERENCE_DETECTED(ValidationSeverity.WARNING),

    /** A coarse whole-page rasterized comparison could not be produced at all (no qualified baseline render exists, or rendering failed) -- reported rather than silently skipped. */
    LAYOUT_COMPARISON_UNAVAILABLE(ValidationSeverity.WARNING);

    private final ValidationSeverity severity;

    ValidationFindingCode(ValidationSeverity severity) {
        this.severity = severity;
    }

    public ValidationSeverity severity() {
        return severity;
    }
}
