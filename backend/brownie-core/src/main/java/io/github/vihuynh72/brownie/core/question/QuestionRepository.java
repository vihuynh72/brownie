package io.github.vihuynh72.brownie.core.question;

import java.util.List;
import java.util.Optional;

/**
 * Persists and resolves questions raised against a document's fields.
 * Every method takes {@code userId} alongside {@code workspaceId}, even a
 * plain read -- a real implementation sets it as this transaction's
 * tenant-context row-level-security actor before touching the table, the
 * same requirement {@code CompilationRepository} already carries, and
 * pooled-connection reuse must never let one request's context leak into
 * another's.
 */
public interface QuestionRepository {

    Question create(long workspaceId, long userId, long documentId, String fieldId, QuestionReason reason, List<QuestionCandidateOption> candidates);

    Optional<Question> find(long workspaceId, long userId, long questionId);

    List<Question> findOpenForDocument(long workspaceId, long userId, long documentId);

    /** Every question ever raised against this document, open or answered, oldest first. */
    List<Question> findAllForDocument(long workspaceId, long userId, long documentId);

    /** Answers exactly one OPEN question. Throws if it does not exist, belongs to another workspace, or is already answered. */
    Question answer(long workspaceId, long userId, long questionId, String answerValue);
}
