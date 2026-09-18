package io.github.vihuynh72.brownie.core.source;

import io.github.vihuynh72.brownie.core.evidence.SourceSpan;

import java.util.Objects;

/**
 * One cited excerpt as a document shows it: the span, the text it
 * resolves to today, the snapshot it cites, and the display filename of
 * the artifact behind that snapshot ({@code null} when the artifact row
 * is no longer readable).
 */
public record DocumentEvidence(SourceSpan span, String excerptText, SourceSnapshot snapshot, String displayFilename) {

    public DocumentEvidence {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(excerptText, "excerptText");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
