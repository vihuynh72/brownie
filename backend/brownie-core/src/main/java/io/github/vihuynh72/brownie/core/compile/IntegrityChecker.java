package io.github.vihuynh72.brownie.core.compile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compares a fill pass's own intended text against two independently
 * observed texts: the reopened DOCX body and the rendered PDF's own
 * extracted text. Whitespace is collapsed before comparing, since a page
 * or line wrap inserted by re-parsing or rendering is a layout detail, not
 * a missing-content defect -- the same reasoning the DOCX-binding spike's
 * own render inspection already applied.
 */
final class IntegrityChecker {

    private IntegrityChecker() {
    }

    static List<IntegrityFinding> check(Map<String, List<String>> intendedText, String reopenedDocxText, String pdfExtractedText) {
        String normalizedDocx = normalize(reopenedDocxText);
        String normalizedPdf = normalize(pdfExtractedText);
        List<IntegrityFinding> findings = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : intendedText.entrySet()) {
            for (String text : entry.getValue()) {
                if (text.isBlank()) {
                    continue;
                }
                String normalizedExpected = normalize(text);
                findings.add(new IntegrityFinding(
                        entry.getKey(),
                        text,
                        normalizedDocx.contains(normalizedExpected),
                        normalizedPdf.contains(normalizedExpected)));
            }
        }
        return findings;
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
