package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/** The versioned per-page geometry and text extracted from one PDF, tagged with the exact parser version that produced it. */
public record PdfStructuralGraph(String parserVersion, List<PdfPage> pages) {
}
