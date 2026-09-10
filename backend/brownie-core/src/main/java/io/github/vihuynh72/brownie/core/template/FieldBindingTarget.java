package io.github.vihuynh72.brownie.core.template;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;

/**
 * Where a {@link FieldDefinition} actually points inside a source DOCX's
 * extracted structure. {@link ContentControlTag} is the built-in-template
 * convention this product picked (a stable named content control -- see the
 * DOCX-binding spike and {@code PoiDocxStructuralExtractor}); {@link
 * StructuralNode} is the more general fallback for a custom template's
 * detected node, scoped to one document part because a bare {@code nodeId}
 * (a sibling-index path like {@code "p2/sdt0/r0"}) is only unique within
 * the part it was extracted from, not across the whole document.
 */
public sealed interface FieldBindingTarget {

    record ContentControlTag(String tag) implements FieldBindingTarget {
    }

    record StructuralNode(DocumentPartKind part, String nodeId) implements FieldBindingTarget {
    }
}
