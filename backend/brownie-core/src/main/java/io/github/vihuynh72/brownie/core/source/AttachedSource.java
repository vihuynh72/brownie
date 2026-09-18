package io.github.vihuynh72.brownie.core.source;

import java.util.Objects;

/**
 * A document's source as the workspace shows it: the link itself, the
 * snapshot it points at, and the display filename of the artifact behind
 * that snapshot ({@code null} when the artifact row is no longer readable).
 */
public record AttachedSource(DocumentSource link, SourceSnapshot snapshot, String displayFilename) {

    public AttachedSource {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
