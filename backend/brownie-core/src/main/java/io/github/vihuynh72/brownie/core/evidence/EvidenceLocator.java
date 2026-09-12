package io.github.vihuynh72.brownie.core.evidence;

/**
 * Where a cited excerpt lives within a source's own extraction graph, in
 * the shape each format's extraction actually addresses content by --
 * matching the plan's own per-format evidence-locator contract exactly:
 * a DOCX locator names a package part and a structural node's own stable
 * path (see {@code StructuralNode.nodeId}); a PDF locator names a
 * one-based page and the line index this extractor grouped text into (see
 * {@code PdfTextLine.lineIndex}); plain text has no structural units at
 * all, so its locator is a plain offset range into the source's own
 * normalized text. In every case, {@code startCodePoint}/{@code
 * endCodePointExclusive} address the *node's or line's own text* (DOCX,
 * PDF) or the *whole normalized document text* (plain text) in Unicode
 * code points, never raw {@code char} units -- see {@code CodePoints}.
 */
public sealed interface EvidenceLocator {

    record Docx(String partName, String nodeId, int startCodePoint, int endCodePointExclusive) implements EvidenceLocator {
    }

    record Pdf(int pageNumber, int lineIndex, int startCodePoint, int endCodePointExclusive) implements EvidenceLocator {
    }

    record PlainText(int startCodePoint, int endCodePointExclusive) implements EvidenceLocator {
    }
}
