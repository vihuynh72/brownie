package io.github.vihuynh72.brownie.core.question;

import java.util.List;
import java.util.Objects;

/** One question {@link QuestionDetectionService} found, not yet persisted -- see {@code QuestionService} for the step that assigns it an ID. */
public record DetectedQuestion(String fieldId, QuestionReason reason, List<QuestionCandidateOption> candidates) {

    public DetectedQuestion {
        Objects.requireNonNull(fieldId, "fieldId");
        candidates = List.copyOf(candidates);
    }
}
