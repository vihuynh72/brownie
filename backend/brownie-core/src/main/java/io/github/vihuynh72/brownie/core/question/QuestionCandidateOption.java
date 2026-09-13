package io.github.vihuynh72.brownie.core.question;

import java.util.List;
import java.util.Objects;

/**
 * One alternative shown to the person answering a question -- descriptive
 * context, not an executable structure. For a {@link QuestionReason#CONFLICT}
 * question this is typically two options (the document's own current value
 * and the newly extracted one); for {@link QuestionReason#MISSING_REQUIRED}
 * it is usually empty, or one low-confidence guess the model itself flagged
 * as unresolved.
 */
public record QuestionCandidateOption(String value, List<Long> evidenceSpanIds) {

    public QuestionCandidateOption {
        Objects.requireNonNull(value, "value");
        evidenceSpanIds = List.copyOf(evidenceSpanIds);
    }
}
