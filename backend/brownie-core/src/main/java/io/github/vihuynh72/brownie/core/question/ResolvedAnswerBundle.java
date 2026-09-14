package io.github.vihuynh72.brownie.core.question;

import java.util.List;
import java.util.Objects;

/**
 * The API's own answer to {@link DetectedQuestionsBundle}, staged the
 * moment a resume is requested: every currently {@link
 * QuestionStatus#ANSWERED} question for the document, reduced to just the
 * field it settles and the exact value and evidence the worker should
 * treat as final -- not a full {@link Question} row, which carries
 * identifiers (workspace, document, question ID) the worker has no tenant
 * context to make sense of. On its next claim, the worker folds this
 * bundle's own answers into a freshly extracted {@code ExtractionResult}
 * before asking {@link QuestionDetectionService} anything again, so an
 * already-settled field is never re-asked regardless of what a new
 * extraction attempt itself proposes for it.
 */
public record ResolvedAnswerBundle(List<ResolvedAnswer> answers) {

    public ResolvedAnswerBundle {
        answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
    }

    public record ResolvedAnswer(String fieldId, String answerValue, List<Long> evidenceSpanIds) {

        public ResolvedAnswer {
            Objects.requireNonNull(fieldId, "fieldId");
            Objects.requireNonNull(answerValue, "answerValue");
            evidenceSpanIds = List.copyOf(Objects.requireNonNull(evidenceSpanIds, "evidenceSpanIds"));
        }

        public static ResolvedAnswer from(Question answered) {
            return new ResolvedAnswer(answered.fieldId(), answered.answerValue(), answered.answeredEvidenceSpanIds());
        }
    }
}
