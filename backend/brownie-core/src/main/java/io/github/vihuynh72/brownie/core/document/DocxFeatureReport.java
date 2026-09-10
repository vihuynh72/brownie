package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/** Every unsupported feature found while walking a DOCX. Empty means the document is within the qualified subset. */
public record DocxFeatureReport(List<DocxFeatureFinding> findings) {

    private static final DocxFeatureReport EMPTY = new DocxFeatureReport(List.of());

    public static DocxFeatureReport empty() {
        return EMPTY;
    }

    public boolean isSupported() {
        return findings.isEmpty();
    }
}
