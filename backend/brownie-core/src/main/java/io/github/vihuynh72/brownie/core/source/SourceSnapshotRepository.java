package io.github.vihuynh72.brownie.core.source;

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
}
