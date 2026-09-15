package io.github.vihuynh72.brownie.core.question;

import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one extraction's scalar candidates into typed questions: a
 * required field the model could not resolve becomes a {@link
 * QuestionReason#MISSING_REQUIRED} question; a resolved candidate that
 * disagrees with the document's own current value for that field becomes
 * a {@link QuestionReason#CONFLICT} question naming both alternatives.
 * Pure and stateless -- no repository, no model call, directly unit-
 * testable with hand-built inputs.
 *
 * <p>Repeated fields are deliberately out of scope: comparing a proposed
 * list of items against an existing list for "conflict" requires matching
 * items across the two lists first, a real, separate problem this task
 * does not solve. An unresolved required scalar still raises a question
 * exactly as described above regardless of this gap.
 */
public final class QuestionDetectionService {

    private QuestionDetectionService() {
    }

    public static List<DetectedQuestion> detect(ExtractionResult result, List<FieldDefinition> fieldDefinitions, DocumentContent existingContent) {
        List<DetectedQuestion> questions = new ArrayList<>();
        for (FieldDefinition field : fieldDefinitions) {
            if (field.cardinality() != FieldCardinality.SCALAR) {
                continue;
            }
            FieldCandidate candidate = result.scalarCandidates().get(field.fieldId());
            if (candidate == null) {
                continue;
            }
            if (candidate.unresolved()) {
                if (field.requiredness() == FieldRequiredness.REQUIRED) {
                    questions.add(new DetectedQuestion(field.fieldId(), QuestionReason.MISSING_REQUIRED, List.of()));
                }
                continue;
            }
            detectConflict(field, candidate, existingContent).ifPresent(questions::add);
        }
        return List.copyOf(questions);
    }

    private static java.util.Optional<DetectedQuestion> detectConflict(
            FieldDefinition field, FieldCandidate candidate, DocumentContent existingContent) {
        FieldValue existing = existingContent.fields().get(field.fieldId());
        if (existing == null) {
            return java.util.Optional.empty();
        }
        String existingAsText = existing.asPlainText();
        if (existingAsText == null || existingAsText.equals(candidate.value())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new DetectedQuestion(
                field.fieldId(),
                QuestionReason.CONFLICT,
                List.of(
                        new QuestionCandidateOption(existingAsText, List.of()),
                        new QuestionCandidateOption(candidate.value(), candidate.evidenceSpanIds()))));
    }
}
