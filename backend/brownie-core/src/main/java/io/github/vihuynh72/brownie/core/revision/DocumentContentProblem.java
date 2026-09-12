package io.github.vihuynh72.brownie.core.revision;

import java.util.Objects;

/** One field-level validation failure in typed document content. */
public record DocumentContentProblem(String fieldId, DocumentContentProblemReason reason, String detail) {

    public DocumentContentProblem {
        Objects.requireNonNull(fieldId, "fieldId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(detail, "detail");
    }
}
