package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/**
 * A filled PDF and what filling it found. A value with a blocking finding
 * was not written at all (never half-written or cut short); {@link
 * #wrote(String)} says which values were.
 */
public record FilledPdf(byte[] bytes, List<PdfFillFinding> findings) {

    public FilledPdf {
        findings = List.copyOf(findings);
    }

    /** Whether the value with this field id was written: it has no blocking finding. */
    public boolean wrote(String fieldId) {
        return findings.stream().noneMatch(finding -> finding.blocking() && fieldId.equals(finding.fieldId()));
    }

    public boolean blocked() {
        return findings.stream().anyMatch(PdfFillFinding::blocking);
    }
}
