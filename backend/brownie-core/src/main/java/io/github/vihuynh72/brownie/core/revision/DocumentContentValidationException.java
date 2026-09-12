package io.github.vihuynh72.brownie.core.revision;

import java.util.List;
import java.util.Objects;

/** Raised before a malformed typed edit or content snapshot can be stored. */
public class DocumentContentValidationException extends RuntimeException {

    private final List<DocumentContentProblem> problems;

    public DocumentContentValidationException(List<DocumentContentProblem> problems) {
        super("Document content is invalid: " + Objects.requireNonNull(problems, "problems"));
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("Document content validation failures must not be empty.");
        }
        this.problems = List.copyOf(problems);
    }

    public List<DocumentContentProblem> problems() {
        return problems;
    }
}
