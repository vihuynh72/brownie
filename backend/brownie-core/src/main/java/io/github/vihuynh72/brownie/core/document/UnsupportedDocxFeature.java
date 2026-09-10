package io.github.vihuynh72.brownie.core.document;

/** A feature the qualified DOCX subset does not support, detected while extracting a document's structure. */
public enum UnsupportedDocxFeature {
    TRACKED_CHANGES,
    UNRESOLVED_COMMENT,
    FLOATING_SHAPE,
    NESTED_TABLE,
    LINKED_EXTERNAL_IMAGE,
    EMBEDDED_OBJECT,
    UNSUPPORTED_FIELD,
    PACKAGE_SIGNATURE
}
