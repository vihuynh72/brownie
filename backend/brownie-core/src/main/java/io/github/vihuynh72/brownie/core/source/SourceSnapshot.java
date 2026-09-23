package io.github.vihuynh72.brownie.core.source;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * An artifact designated as evidence at a point in time. Immutable once
 * created: {@code artifactId} itself already points at bytes that cannot
 * change after finalization, and this row does not either. Attaching a
 * snapshot records the fact that an artifact was designated as a source --
 * it does not itself guarantee anything can be cited from it yet; that
 * depends on whether the artifact's own extraction actually produced
 * usable content, checked separately when a span is created against it.
 *
 * <p>{@code origin} is where a copied source came from, present exactly
 * when the kind is one that is copied from somewhere, and null for an
 * upload.
 */
public record SourceSnapshot(long id, long workspaceId, long artifactId, SourceKind kind, OffsetDateTime fetchedAt, SourceOrigin origin) {

    public SourceSnapshot {
        Objects.requireNonNull(kind, "kind");
        if ((kind == SourceKind.ARTIFACT) != (origin == null)) {
            throw new IllegalArgumentException("A " + kind + " snapshot " + (origin == null ? "needs" : "cannot have") + " an origin.");
        }
    }

    /** An upload, which has no origin elsewhere. */
    public SourceSnapshot(long id, long workspaceId, long artifactId, SourceKind kind, OffsetDateTime fetchedAt) {
        this(id, workspaceId, artifactId, kind, fetchedAt, null);
    }
}
