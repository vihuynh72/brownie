package io.github.vihuynh72.brownie.core.source;

import java.time.Instant;
import java.util.Optional;

/** Every method takes workspace and user context explicitly, the same tenant-scoped pattern every other repository in this codebase follows. */
public interface SourceSnapshotRepository {

    Optional<SourceSnapshot> find(long workspaceId, long userId, long snapshotId);

    /** One artifact ever has at most one snapshot -- attaching it again returns the same row rather than creating a duplicate. */
    Optional<SourceSnapshot> findByArtifact(long workspaceId, long userId, long artifactId);

    /**
     * Insert-or-return-existing, safe under two concurrent callers
     * attaching the same artifact: an artifact being designated as a
     * source is a fact about that artifact, not a per-call side effect, so
     * either caller converging on the same single row is correct, the
     * same reasoning the extraction-version repositories already apply to
     * their own concurrent saves.
     */
    SourceSnapshot create(long workspaceId, long userId, long artifactId, SourceKind kind);

    /**
     * Records a copied artifact as a source with where it came from, and links
     * it to the document, as one step: a document deleted for good while the
     * copy was being made leaves neither behind. The same version of the same
     * thing, read through the same choice, is one snapshot: when one already
     * exists, it is linked and returned and {@code artifactId} is not made a
     * source (its bytes are then left for the worker to clear away, as an
     * unused upload is). {@code fetchedAt} is when the provider was read,
     * which can be well before the row is written. Empty when the connection
     * or the choice it was read through is no longer open at the moment of
     * writing, because the person disconnected or took it back while the copy
     * was being made; nothing is recorded then.
     */
    Optional<SourceSnapshot> createImported(
            long workspaceId, long userId, long documentId, long artifactId, SourceKind kind, SourceOrigin origin, Instant fetchedAt);

    /** The snapshot already copied from this version of this thing through this choice, if there is one. */
    Optional<SourceSnapshot> findImported(long workspaceId, long userId, long grantId, String externalId, String revision);

    /**
     * The most recent snapshot copied from this thing through this choice, if
     * its bytes have this SHA-256 (lowercase hex), whatever the provider's
     * version was then: the same content read again is not a new copy. Only
     * the most recent is compared, so content that changes and then changes
     * back is a new copy, not the older one brought back.
     */
    Optional<SourceSnapshot> findLatestImportedWithContent(
            long workspaceId, long userId, long grantId, String externalId, String sha256Hex);
}
