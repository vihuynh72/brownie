package io.github.vihuynh72.brownie.core.question;

import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;

import java.util.List;

/**
 * Detects and persists the questions one extraction raises against one
 * document, and resolves them with a person's typed answer. Depends only
 * on {@link QuestionRepository}, so it has no framework or database
 * dependency of its own -- the same dependency-inversion shape {@code
 * CompilationService} already establishes.
 */
public class QuestionService {

    private final QuestionRepository questionRepository;

    public QuestionService(QuestionRepository questionRepository) {
        this.questionRepository = questionRepository;
    }

    public List<Question> detectAndPersist(
            long workspaceId, long userId, long documentId, ExtractionResult result, List<FieldDefinition> fieldDefinitions,
            DocumentContent existingContent) {
        List<DetectedQuestion> detected = QuestionDetectionService.detect(result, fieldDefinitions, existingContent);
        return detected.stream()
                .map(d -> questionRepository.create(workspaceId, userId, documentId, null, null, d.fieldId(), d.reason(), d.candidates()))
                .toList();
    }

    public List<Question> openQuestions(long workspaceId, long userId, long documentId) {
        return questionRepository.findOpenForDocument(workspaceId, userId, documentId);
    }

    public List<Question> allQuestions(long workspaceId, long userId, long documentId) {
        return questionRepository.findAllForDocument(workspaceId, userId, documentId);
    }

    /** Every question one generation run raised, open or answered, oldest first. */
    public List<Question> allQuestionsForRun(long workspaceId, long userId, long generationRunId) {
        return questionRepository.findAllForRun(workspaceId, userId, generationRunId);
    }

    /**
     * Persists a worker's own already-detected questions directly, skipping
     * {@link QuestionDetectionService#detect}: the worker ran detection
     * itself (it has the freshly extracted candidates; this service does
     * not), so this is the "persist" half of {@link #detectAndPersist}
     * alone, for a caller handed a {@link DetectedQuestionsBundle} instead
     * of a raw {@code ExtractionResult}. Each row records the run and the
     * attempt that raised it.
     */
    public List<Question> persistDetected(
            long workspaceId, long userId, long documentId, long generationRunId, long attemptFencingToken, List<DetectedQuestion> detected) {
        return detected.stream()
                .map(d -> questionRepository.create(
                        workspaceId, userId, documentId, generationRunId, attemptFencingToken, d.fieldId(), d.reason(), d.candidates()))
                .toList();
    }

    public Question answer(long workspaceId, long userId, long questionId, String answerValue) {
        if (answerValue == null || answerValue.isBlank()) {
            throw new IllegalArgumentException("An answer must not be blank.");
        }
        return questionRepository.answer(workspaceId, userId, questionId, answerValue);
    }
}
