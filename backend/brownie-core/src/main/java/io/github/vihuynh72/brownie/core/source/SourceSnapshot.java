package io.github.vihuynh72.brownie.core.source;

import java.time.OffsetDateTime;

/**
 * An artifact designated as evidence at a point in time. Immutable once
 * created: {@code artifactId} itself already points at bytes that cannot
 * change after finalization, and this row does not either. Attaching a
 * snapshot records the fact that an artifact was designated as a source --
 * it does not itself guarantee anything can be cited from it yet; that
 * depends on whether the artifact's own extraction actually produced
 * usable content, checked separately when a span is created against it.
 */
public record SourceSnapshot(long id, long workspaceId, long artifactId, SourceKind kind, OffsetDateTime fetchedAt) {
}
