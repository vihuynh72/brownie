package io.github.vihuynh72.brownie.core.template;

/**
 * One typed, bound field within a {@link TemplateVersion}. {@code fieldId}
 * is stable across a template's own versions (a later phase's rules and a
 * document's field revisions address a field by this ID, not by its
 * position), unique within the version it belongs to.
 */
public record FieldDefinition(
        String fieldId,
        FieldType type,
        FieldCardinality cardinality,
        FieldRequiredness requiredness,
        FieldBindingTarget binding) {
}
