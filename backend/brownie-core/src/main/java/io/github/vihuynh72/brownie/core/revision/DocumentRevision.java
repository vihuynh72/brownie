package io.github.vihuynh72.brownie.core.revision;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable snapshot of a document's typed field content. {@code
 * evidence} names, for a subset of the fields in {@code content}, which
 * already-persisted {@code SourceSpan} IDs the actor says back that field's
 * value -- a user-asserted citation, not a validated or AI-composed support
 * relationship (that dimension belongs to later, model-assisted work). It is
 * kept separate from {@code content}/{@code contentHash} because provenance
 * about a value is not part of the value's own typed identity: two revisions
 * can carry the identical field values while citing different evidence.
 */
public record DocumentRevision(
        long id,
        long workspaceId,
        long documentId,
        int revisionNumber,
        Long parentRevisionId,
        DocumentContent content,
        String contentHash,
        long actorUserId,
        String editReason,
        OffsetDateTime createdAt,
        Map<String, List<Long>> evidence) {

    public DocumentRevision {
        if (revisionNumber < 1) {
            throw new IllegalArgumentException("Revision number must be positive.");
        }
        Objects.requireNonNull(content, "content");
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Content hash must be a lowercase SHA-256 hex value.");
        }
        if (editReason == null || editReason.isBlank()) {
            throw new IllegalArgumentException("Edit reason must not be blank.");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        evidence = copyOfEvidence(evidence);
    }

    private static Map<String, List<Long>> copyOfEvidence(Map<String, List<Long>> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Long>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : evidence.entrySet()) {
            String fieldId = entry.getKey();
            if (fieldId == null || fieldId.isBlank()) {
                throw new IllegalArgumentException("Evidence field IDs must not be blank.");
            }
            List<Long> spanIds = List.copyOf(Objects.requireNonNull(entry.getValue(), "evidence source span IDs"));
            if (spanIds.isEmpty()) {
                throw new IllegalArgumentException("Evidence for field " + fieldId + " must not be an empty list.");
            }
            for (Long spanId : spanIds) {
                if (spanId == null || spanId <= 0) {
                    throw new IllegalArgumentException("Evidence source span IDs must be positive.");
                }
            }
            copied.put(fieldId, spanIds);
        }
        return Map.copyOf(copied);
    }
}
