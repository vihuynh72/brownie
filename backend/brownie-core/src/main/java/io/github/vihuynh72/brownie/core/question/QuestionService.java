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
                .map(d -> questionRepository.create(workspaceId, userId, documentId, d.fieldId(), d.reason(), d.candidates()))
                .toList();
    }

    public List<Question> openQuestions(long workspaceId, long userId, long documentId) {
        return questionRepository.findOpenForDocument(workspaceId, userId, documentId);
    }

    public Question answer(long workspaceId, long userId, long questionId, String answerValue) {
        if (answerValue == null || answerValue.isBlank()) {
            throw new IllegalArgumentException("An answer must not be blank.");
        }
        return questionRepository.answer(workspaceId, userId, questionId, answerValue);
    }
}
