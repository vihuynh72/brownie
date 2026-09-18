package io.github.vihuynh72.brownie.core.question;

import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link AcceptedFactsReconciler}, the opposite end of {@code QuestionDetectionService}'s own lifecycle. */
class AcceptedFactsReconcilerTest {

    private static final String TITLE = "meeting.title";
    private static final String DATE = "meeting.date";

    @Test
    void anAnsweredQuestionReplacesTheFieldWithTheChosenAnswerAndItsMatchingEvidence() {
        FieldCandidate original = new FieldCandidate(TITLE, "Weekly sync", List.of(101L), false, null);
        ExtractionResult extraction = new ExtractionResult(Map.of(TITLE, original), List.of());
        Question answered = answeredQuestion(
                TITLE,
                List.of(new QuestionCandidateOption("Weekly sync", List.of(101L)), new QuestionCandidateOption("Budget sync", List.of(202L))),
                "Budget sync");

        ExtractionResult reconciled = AcceptedFactsReconciler.reconcile(extraction, List.of(answered));

        FieldCandidate result = reconciled.scalarCandidates().get(TITLE);
        assertEquals("Budget sync", result.value());
        assertEquals(List.of(202L), result.evidenceSpanIds());
        assertEquals(false, result.unresolved());
    }

    @Test
    void anAnswerMatchingNoOfferedOptionIsAcceptedWithNoEvidence() {
        FieldCandidate original = new FieldCandidate(TITLE, null, List.of(), true, "not mentioned");
        ExtractionResult extraction = new ExtractionResult(Map.of(TITLE, original), List.of());
        Question answered = answeredQuestion(TITLE, List.of(), "Typed by a person, matching nothing offered");

        ExtractionResult reconciled = AcceptedFactsReconciler.reconcile(extraction, List.of(answered));

        FieldCandidate result = reconciled.scalarCandidates().get(TITLE);
        assertEquals("Typed by a person, matching nothing offered", result.value());
        assertEquals(List.of(), result.evidenceSpanIds());
        assertEquals(false, result.unresolved());
    }

    @Test
    void aFieldWithNoQuestionPassesThroughUnchanged() {
        FieldCandidate original = new FieldCandidate(TITLE, "Weekly sync", List.of(101L), false, null);
        ExtractionResult extraction = new ExtractionResult(Map.of(TITLE, original), List.of());

        ExtractionResult reconciled = AcceptedFactsReconciler.reconcile(extraction, List.of());

        assertEquals(original, reconciled.scalarCandidates().get(TITLE));
    }

    @Test
    void aStillOpenQuestionLeavesTheFieldUnchanged() {
        FieldCandidate original = new FieldCandidate(DATE, null, List.of(), true, "not mentioned");
        ExtractionResult extraction = new ExtractionResult(Map.of(DATE, original), List.of());
        Question open = new Question(1, 1, 1, null, null, DATE, QuestionReason.MISSING_REQUIRED, List.of(), QuestionStatus.OPEN, null, null,
                null, OffsetDateTime.now());

        ExtractionResult reconciled = AcceptedFactsReconciler.reconcile(extraction, List.of(open));

        assertEquals(original, reconciled.scalarCandidates().get(DATE));
        assertTrue(reconciled.scalarCandidates().get(DATE).unresolved());
    }

    private static Question answeredQuestion(String fieldId, List<QuestionCandidateOption> candidates, String answerValue) {
        return new Question(
                1, 1, 1, null, null, fieldId, QuestionReason.CONFLICT, candidates, QuestionStatus.ANSWERED, answerValue, 1L,
                OffsetDateTime.now(), OffsetDateTime.now());
    }
}
