package io.github.vihuynh72.brownie.core.document;

/** One package part's extracted structure. {@code partName} is the real OOXML part name, for example {@code "word/header1.xml"}. */
public record DocumentPart(String partName, DocumentPartKind kind, StructuralNode root) {
}
