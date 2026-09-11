package io.github.vihuynh72.brownie.core.document;

import java.util.Optional;

/** Every method takes workspace and user context explicitly, the same tenant-scoped pattern {@code ArtifactRepository} follows. */
public interface ExtractionVersionRepository {

    Optional<ExtractionVersion> findByArtifact(long workspaceId, long userId, long artifactId, String parserVersion);

    /**
     * Looks up one extraction version by its own ID rather than by
     * re-deriving it from an artifact and the extractor's *current* parser
     * version -- what a caller needs when it already holds a specific,
     * previously pinned {@code extractionVersionId} (for example a template
     * version's own bindings) and must read exactly that immutable row,
     * unaffected by a parser upgrade that happened afterward.
     */
    Optional<ExtractionVersion> findById(long workspaceId, long userId, long extractionVersionId);

    /**
     * Records a successful extraction. If a row for this exact (artifact,
     * parserVersion) pair already exists -- another caller raced this one
     * and won -- returns that existing row unchanged rather than
     * overwriting it: extraction is a pure function of immutable bytes, so
     * either result is an equally valid answer, and only one is kept.
     */
    ExtractionVersion saveComplete(long workspaceId, long userId, long artifactId, String parserVersion, DocxStructuralGraph graph);

    /** Same apply-or-return-existing behavior as {@link #saveComplete}, for a document outside the qualified subset. */
    ExtractionVersion saveUnsupported(long workspaceId, long userId, long artifactId, String parserVersion, DocxFeatureReport featureReport);

    /** Same apply-or-return-existing behavior as {@link #saveComplete}, for a document that could not be parsed at all. */
    ExtractionVersion saveFailed(long workspaceId, long userId, long artifactId, String parserVersion, String failureReason);
}
