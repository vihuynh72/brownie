package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tenant-scoped persistence for a document pointer and immutable revision
 * history. Every lookup carries both workspace and actor context.
 */
public interface DocumentRepository {

    /**
     * Finds the already-applied result for this actor-scoped key. A matching
     * retry can therefore succeed even if the document has advanced since
     * the original mutation.
     */
    Optional<DocumentMutationResult> findMutationResult(
            long workspaceId,
            long userId,
            DocumentCommandType commandType,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash);

    DocumentMutationResult createIdempotently(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            String title,
            long templateId,
            long templateVersionId,
            DocumentContent initialContent,
            Map<String, List<Long>> initialEvidence,
            String initialRevisionReason);

    Optional<Document> find(long workspaceId, long userId, long documentId);

    Optional<DocumentRevision> findCurrentRevision(long workspaceId, long userId, long documentId);

    Optional<DocumentRevision> findRevision(long workspaceId, long userId, long documentId, long revisionId);

    List<DocumentRevision> findHistory(long workspaceId, long userId, long documentId);

    /**
     * Appends one child revision only while {@code expectedRevisionId} is
     * still the document's current pointer. A mismatch writes nothing.
     */
    DocumentMutationResult appendRevisionIdempotently(
            long workspaceId,
            long userId,
            IdempotencyKey idempotencyKey,
            CanonicalRequestHash requestHash,
            long documentId,
            long expectedRevisionId,
            DocumentContent content,
            Map<String, List<Long>> evidence,
            String editReason);
}
