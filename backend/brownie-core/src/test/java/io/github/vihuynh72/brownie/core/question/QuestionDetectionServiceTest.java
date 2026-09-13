package io.github.vihuynh72.brownie.core.question;

import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionDetectionServiceTest {

    private static final FieldDefinition REQUIRED_TITLE = field("meeting.title", FieldRequiredness.REQUIRED);
    private static final FieldDefinition OPTIONAL_LOCATION = field("meeting.location", FieldRequiredness.OPTIONAL);
    private static final FieldDefinition REPEATED_TASK = new FieldDefinition(
            "action.item.task", FieldType.TEXT, FieldCardinality.REPEATED, FieldRequiredness.OPTIONAL,
            new FieldBindingTarget.ContentControlTag("action.item.task"));

    @Test
    void anUnresolvedRequiredFieldRaisesAMissingRequiredQuestion() {
        ExtractionResult result = new ExtractionResult(
                Map.of(REQUIRED_TITLE.fieldId(), unresolved(REQUIRED_TITLE.fieldId())), List.of());

        List<DetectedQuestion> questions =
                QuestionDetectionService.detect(result, List.of(REQUIRED_TITLE), DocumentContent.empty());

        assertEquals(1, questions.size());
        assertEquals(QuestionReason.MISSING_REQUIRED, questions.getFirst().reason());
        assertEquals(REQUIRED_TITLE.fieldId(), questions.getFirst().fieldId());
    }

    @Test
    void anUnresolvedOptionalFieldRaisesNoQuestion() {
        ExtractionResult result = new ExtractionResult(
                Map.of(OPTIONAL_LOCATION.fieldId(), unresolved(OPTIONAL_LOCATION.fieldId())), List.of());

        List<DetectedQuestion> questions =
                QuestionDetectionService.detect(result, List.of(OPTIONAL_LOCATION), DocumentContent.empty());

        assertTrue(questions.isEmpty());
    }

    @Test
    void aResolvedCandidateMatchingTheExistingValueRaisesNoQuestion() {
        ExtractionResult result = new ExtractionResult(
                Map.of(REQUIRED_TITLE.fieldId(), resolved(REQUIRED_TITLE.fieldId(), "Weekly Sync", 1)), List.of());
        DocumentContent existing = new DocumentContent(Map.of(REQUIRED_TITLE.fieldId(), new FieldValue.TextValue("Weekly Sync")));

        List<DetectedQuestion> questions = QuestionDetectionService.detect(result, List.of(REQUIRED_TITLE), existing);

        assertTrue(questions.isEmpty());
    }

    @Test
    void aResolvedCandidateDisagreeingWithTheExistingValueRaisesAConflictQuestionNamingBoth() {
        ExtractionResult result = new ExtractionResult(
                Map.of(REQUIRED_TITLE.fieldId(), resolved(REQUIRED_TITLE.fieldId(), "Weekly Sync v2", 7)), List.of());
        DocumentContent existing = new DocumentContent(Map.of(REQUIRED_TITLE.fieldId(), new FieldValue.TextValue("Weekly Sync")));

        List<DetectedQuestion> questions = QuestionDetectionService.detect(result, List.of(REQUIRED_TITLE), existing);

        assertEquals(1, questions.size());
        DetectedQuestion question = questions.getFirst();
        assertEquals(QuestionReason.CONFLICT, question.reason());
        assertEquals(2, question.candidates().size());
        assertEquals("Weekly Sync", question.candidates().get(0).value());
        assertEquals("Weekly Sync v2", question.candidates().get(1).value());
        assertEquals(List.of(7L), question.candidates().get(1).evidenceSpanIds());
    }

    @Test
    void aResolvedDateCandidateIsComparedAgainstTheExistingDatesTextForm() {
        FieldDefinition dateField = new FieldDefinition(
                "meeting.date", FieldType.DATE, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.date"));
        ExtractionResult result = new ExtractionResult(Map.of(dateField.fieldId(), resolved(dateField.fieldId(), "2026-03-05", 1)), List.of());
        DocumentContent existing = new DocumentContent(Map.of(dateField.fieldId(), new FieldValue.DateValue(LocalDate.of(2026, 3, 5))));

        List<DetectedQuestion> questions = QuestionDetectionService.detect(result, List.of(dateField), existing);

        assertTrue(questions.isEmpty());
    }

    @Test
    void repeatedFieldsAreNeverInspectedForConflictOrMissingRequired() {
        ExtractionResult result = new ExtractionResult(Map.of(), List.of());

        List<DetectedQuestion> questions = QuestionDetectionService.detect(result, List.of(REPEATED_TASK), DocumentContent.empty());

        assertTrue(questions.isEmpty());
    }

    @Test
    void aFieldAbsentFromTheExtractionResultRaisesNothing() {
        ExtractionResult result = new ExtractionResult(Map.of(), List.of());

        List<DetectedQuestion> questions = QuestionDetectionService.detect(result, List.of(REQUIRED_TITLE), DocumentContent.empty());

        assertTrue(questions.isEmpty());
    }

    private static FieldDefinition field(String fieldId, FieldRequiredness requiredness) {
        return new FieldDefinition(fieldId, FieldType.TEXT, FieldCardinality.SCALAR, requiredness, new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static FieldCandidate unresolved(String fieldId) {
        return new FieldCandidate(fieldId, null, List.of(), true, "not mentioned in the source");
    }

    private static FieldCandidate resolved(String fieldId, String value, long spanId) {
        return new FieldCandidate(fieldId, value, List.of(spanId), false, null);
    }
}
