package io.github.vihuynh72.brownie.core.question;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * One typed question raised against one document's field. {@code
 * candidates} is fixed at creation time; answering only ever sets {@code
 * answerValue}/{@code answeredByUserId}/{@code answeredAt} together, and
 * only once -- see {@code QuestionRepository#answer} and this table's own
 * database-level consistency check.
 */
public record Question(
        long id,
        long workspaceId,
        long documentId,
        String fieldId,
        QuestionReason reason,
        List<QuestionCandidateOption> candidates,
        QuestionStatus status,
        String answerValue,
        Long answeredByUserId,
        OffsetDateTime answeredAt,
        OffsetDateTime createdAt) {

    public Question {
        Objects.requireNonNull(fieldId, "fieldId");
        if (fieldId.isBlank()) {
            throw new IllegalArgumentException("fieldId must not be blank");
        }
        candidates = List.copyOf(candidates);
        boolean answered = status == QuestionStatus.ANSWERED;
        if (answered != (answerValue != null) || answered != (answeredByUserId != null) || answered != (answeredAt != null)) {
            throw new IllegalArgumentException("A question's answer fields must be all-present when ANSWERED and all-absent when OPEN.");
        }
    }
}
