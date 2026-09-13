package io.github.vihuynh72.brownie.core.question;

import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one extraction's raw candidates into the final accepted-facts view
 * a composer may draw on: a field with an {@link QuestionStatus#ANSWERED}
 * question is replaced with the person's chosen answer, carrying forward
 * whichever offered {@link QuestionCandidateOption}'s own evidence
 * matches that exact answer value, or no evidence at all when the person
 * typed something new that matches no offered option -- a manually
 * settled fact is real, just possibly uncited, the same way a user's own
 * typed edit can be. A field with no question, or with a still-{@link
 * QuestionStatus#OPEN} one, passes through unchanged. Pure and stateless,
 * mirroring {@link QuestionDetectionService}'s own shape at the opposite
 * end of the same lifecycle: that class turns raw candidates into
 * questions; this one turns answered questions back into facts.
 */
public final class AcceptedFactsReconciler {

    private AcceptedFactsReconciler() {
    }

    public static ExtractionResult reconcile(ExtractionResult extraction, List<Question> questions) {
        Map<String, Question> answeredByFieldId = new LinkedHashMap<>();
        for (Question question : questions) {
            if (question.status() == QuestionStatus.ANSWERED) {
                answeredByFieldId.put(question.fieldId(), question);
            }
        }

        Map<String, FieldCandidate> reconciled = new LinkedHashMap<>();
        extraction.scalarCandidates().forEach((fieldId, candidate) -> {
            Question answered = answeredByFieldId.get(fieldId);
            reconciled.put(fieldId, answered == null ? candidate : reconcileField(fieldId, answered));
        });
        return new ExtractionResult(reconciled, extraction.repeatedItems());
    }

    private static FieldCandidate reconcileField(String fieldId, Question answered) {
        List<Long> evidence = answered.candidates().stream()
                .filter(option -> Objects.equals(answered.answerValue(), option.value()))
                .map(QuestionCandidateOption::evidenceSpanIds)
                .findFirst()
                .orElse(List.of());
        return new FieldCandidate(fieldId, answered.answerValue(), evidence, false, null);
    }
}
