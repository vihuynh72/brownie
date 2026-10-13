package io.github.vihuynh72.brownie.core.batch;

/**
 * The step's pinned template version is gone -- the document the row would
 * make cannot be checked against anything. Rows stay PENDING so a fixed
 * template can pick the step up again.
 */
public class BatchTemplateUnavailableException extends RuntimeException {

    public BatchTemplateUnavailableException() {
        super("This batch's template version is no longer available.");
    }
}
