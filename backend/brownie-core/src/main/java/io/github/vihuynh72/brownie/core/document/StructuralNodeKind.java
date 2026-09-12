package io.github.vihuynh72.brownie.core.document;

/** What kind of thing one {@link StructuralNode} represents inside an extracted DOCX part. */
public enum StructuralNodeKind {
    /** The synthetic root of a {@link DocumentPart}'s tree, wrapping that part's top-level children. Never a real OOXML element. */
    BODY,
    PARAGRAPH,
    RUN,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    CONTENT_CONTROL,
    IMAGE
}
