package io.github.vihuynh72.brownie.core.document;

import java.util.List;

/**
 * One node of an extracted DOCX's structure, addressable by {@code nodeId}
 * within the {@link DocumentPart} that contains it. {@code nodeId} is a
 * stable path built from each ancestor's kind and sibling index (for
 * example {@code "p2/sdt0/r0"}: the third top-level paragraph's first
 * content control's first run) -- reproducible from the same bytes and
 * parser version, but not a real OOXML XPath and not guaranteed to survive
 * a document edit.
 *
 * <p>{@code style} is populated only for PARAGRAPH and RUN nodes. {@code
 * text} is populated only for RUN nodes carrying literal text. {@code
 * contentControlTag} is populated only for CONTENT_CONTROL nodes, whose
 * own text lives on their child RUN node(s), not on the control itself.
 * {@code imageRelationshipId} is populated only for IMAGE nodes. This
 * mirrors how {@code Artifact} itself already carries several fields that
 * are only meaningful for some of its states.
 */
public record StructuralNode(
        String nodeId,
        StructuralNodeKind kind,
        ResolvedStyle style,
        String text,
        String contentControlTag,
        String imageRelationshipId,
        List<StructuralNode> children) {
}
