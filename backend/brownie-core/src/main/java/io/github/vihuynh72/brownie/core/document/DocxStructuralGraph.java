package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/**
 * The versioned structural graph produced from one DOCX's supported parts:
 * every extracted package part's node tree, tagged with the exact parser
 * version that produced it so a later parser change is visible rather than
 * silently replacing what earlier evidence was built against.
 */
public record DocxStructuralGraph(String parserVersion, List<DocumentPart> parts) {
}
