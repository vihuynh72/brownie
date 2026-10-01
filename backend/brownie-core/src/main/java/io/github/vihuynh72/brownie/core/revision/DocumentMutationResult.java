package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable result of one document mutation. A matching retry resolves to
 * this same command and revision instead of creating another revision.
 * {@code replayed} says the result answers for an earlier request with the
 * same key, found either before writing or while writing (the earlier
 * request finished first), so that what follows a write is done once.
 */
public record DocumentMutationResult(
        UUID commandId,
        DocumentCommandType commandType,
        Document document,
        DocumentRevision revision,
        CanonicalRequestHash requestHash,
        OffsetDateTime acceptedAt,
        boolean replayed) {

    /** A result this request wrote itself. */
    public DocumentMutationResult(
            UUID commandId,
            DocumentCommandType commandType,
            Document document,
            DocumentRevision revision,
            CanonicalRequestHash requestHash,
            OffsetDateTime acceptedAt) {
        this(commandId, commandType, document, revision, requestHash, acceptedAt, false);
    }

    public DocumentMutationResult {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (document.id() != revision.documentId()) {
            throw new IllegalArgumentException("Mutation result revision must belong to its document.");
        }
    }
}
