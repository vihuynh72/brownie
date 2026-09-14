package io.github.vihuynh72.brownie.core.template;

/**
 * One field binding {@link FieldBindingCandidateProposer} proposes from a
 * custom template's own extracted structure -- observed from the document
 * (a content control exists, carrying this exact tag), never a claim about
 * the field's business meaning or whether it is required. {@code fieldId}
 * is the content control's own tag text, unedited: a real custom template's
 * author chose it, not this codebase's own dotted-name convention, so it is
 * offered back exactly as found rather than reshaped to look like a
 * built-in field ID. A human still reviews, renames, retypes, or discards
 * every candidate before any of it is submitted through {@link
 * TemplateService#replaceDraftBindings}; nothing here writes to a draft by
 * itself.
 */
public record CandidateFieldBinding(String fieldId, FieldType type, FieldCardinality cardinality, FieldBindingTarget binding) {
}
