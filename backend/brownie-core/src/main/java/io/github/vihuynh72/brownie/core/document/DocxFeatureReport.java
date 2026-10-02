package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/**
 * Every feature worth naming found while walking a DOCX, in document order.
 * A document is supported when none of them is refused; the kinds the file
 * may keep as they are ({@link UnsupportedDocxFeature#keptAsIs()}) are
 * reported beside a complete graph, not instead of one.
 */
public record DocxFeatureReport(List<DocxFeatureFinding> findings) {

    private static final DocxFeatureReport EMPTY = new DocxFeatureReport(List.of());

    public static DocxFeatureReport empty() {
        return EMPTY;
    }

    public boolean isSupported() {
        return findings.stream().allMatch(finding -> finding.feature().keptAsIs());
    }

    /** The findings that stop the read. */
    public List<DocxFeatureFinding> refused() {
        return findings.stream().filter(finding -> !finding.feature().keptAsIs()).toList();
    }

    /** The findings the file keeps as they are. */
    public List<DocxFeatureFinding> keptAsIs() {
        return findings.stream().filter(finding -> finding.feature().keptAsIs()).toList();
    }
}
