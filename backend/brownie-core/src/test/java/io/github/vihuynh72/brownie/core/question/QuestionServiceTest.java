package io.github.vihuynh72.brownie.core.question;

import io.github.vihuynh72.brownie.core.generation.ExtractionResult;
import io.github.vihuynh72.brownie.core.generation.FieldCandidate;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionServiceTest {

    private static final FieldDefinition REQUIRED_TITLE = new FieldDefinition(
            "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
            new FieldBindingTarget.ContentControlTag("meeting.title"));
    private static final long WORKSPACE_ID = 1;
    private static final long USER_ID = 2;
    private static final long DOCUMENT_ID = 3;

    @Test
    void detectAndPersistCreatesOneRowPerDetectedQuestion() {
        FakeQuestionRepository repository = new FakeQuestionRepository();
        QuestionService service = new QuestionService(repository);
        ExtractionResult result = new ExtractionResult(
                Map.of(REQUIRED_TITLE.fieldId(), new FieldCandidate(REQUIRED_TITLE.fieldId(), null, List.of(), true, "not mentioned")), List.of());

        List<Question> created =
                service.detectAndPersist(WORKSPACE_ID, USER_ID, DOCUMENT_ID, result, List.of(REQUIRED_TITLE), DocumentContent.empty());

        assertEquals(1, created.size());
        assertEquals(QuestionReason.MISSING_REQUIRED, created.getFirst().reason());
        assertEquals(1, repository.rows.size());
    }

    @Test
    void answerRejectsABlankAnswerBeforeEverCallingTheRepository() {
        FakeQuestionRepository repository = new FakeQuestionRepository();
        QuestionService service = new QuestionService(repository);

        assertThrows(IllegalArgumentException.class, () -> service.answer(WORKSPACE_ID, USER_ID, 5, "   "));
        assertTrue(repository.rows.isEmpty());
    }

    @Test
    void answerDelegatesToTheRepositoryForAGenuineAnswer() {
        FakeQuestionRepository repository = new FakeQuestionRepository();
        QuestionService service = new QuestionService(repository);
        Question open = repository.create(WORKSPACE_ID, USER_ID, DOCUMENT_ID, REQUIRED_TITLE.fieldId(), QuestionReason.MISSING_REQUIRED, List.of());

        Question answered = service.answer(WORKSPACE_ID, USER_ID, open.id(), "Weekly Sync");

        assertEquals(QuestionStatus.ANSWERED, answered.status());
        assertEquals("Weekly Sync", answered.answerValue());
    }

    /** A minimal in-memory double, the same convention {@code RevisionServiceTest}'s own FakeDocumentRepository already establishes. */
    private static final class FakeQuestionRepository implements QuestionRepository {

        private final List<Question> rows = new ArrayList<>();
        private final AtomicLong nextId = new AtomicLong(1);

        @Override
        public Question create(long workspaceId, long userId, long documentId, String fieldId, QuestionReason reason, List<QuestionCandidateOption> candidates) {
            Question question = new Question(
                    nextId.getAndIncrement(), workspaceId, documentId, fieldId, reason, candidates, QuestionStatus.OPEN, null, null, null,
                    OffsetDateTime.now());
            rows.add(question);
            return question;
        }

        @Override
        public Optional<Question> find(long workspaceId, long userId, long questionId) {
            return rows.stream().filter(q -> q.workspaceId() == workspaceId && q.id() == questionId).findFirst();
        }

        @Override
        public List<Question> findOpenForDocument(long workspaceId, long userId, long documentId) {
            return rows.stream()
                    .filter(q -> q.workspaceId() == workspaceId && q.documentId() == documentId && q.status() == QuestionStatus.OPEN)
                    .toList();
        }

        @Override
        public Question answer(long workspaceId, long userId, long questionId, String answerValue) {
            Question existing = find(workspaceId, userId, questionId)
                    .filter(q -> q.status() == QuestionStatus.OPEN)
                    .orElseThrow(() -> new QuestionNotFoundException(questionId));
            Question answered = new Question(
                    existing.id(), existing.workspaceId(), existing.documentId(), existing.fieldId(), existing.reason(),
                    existing.candidates(), QuestionStatus.ANSWERED, answerValue, userId, OffsetDateTime.now(), existing.createdAt());
            rows.remove(existing);
            rows.add(answered);
            return answered;
        }
    }
}
