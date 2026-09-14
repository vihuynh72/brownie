package io.github.vihuynh72.brownie.core.template;

import java.util.List;

/**
 * A sample-content baseline render did not pass its own content-integrity
 * check -- at least one field's intended sample text did not survive into
 * the reopened DOCX or the rendered PDF. Activation refuses rather than
 * activating a version this codebase cannot itself prove actually fills
 * and renders correctly.
 */
public class TemplateBaselineIntegrityException extends RuntimeException {

    private final List<String> failedFieldIds;

    public TemplateBaselineIntegrityException(List<String> failedFieldIds) {
        super("Baseline render failed its own content-integrity check for field(s): " + failedFieldIds);
        this.failedFieldIds = List.copyOf(failedFieldIds);
    }

    public List<String> failedFieldIds() {
        return failedFieldIds;
    }
}
