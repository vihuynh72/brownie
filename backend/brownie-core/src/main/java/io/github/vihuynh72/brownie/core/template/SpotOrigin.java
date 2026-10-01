package io.github.vihuynh72.brownie.core.template;

/**
 * Who placed a fill spot. {@code FORM} spots came with the document (a
 * named control, a form field); {@code FOUND_BY_BROWNIE} spots were found
 * in it by Brownie, and are shown as such until the person keeps them;
 * {@code ADDED_BY_PERSON} spots were placed by the person themselves. A
 * {@link FieldDefinition} stored before this existed has none, which reads
 * as {@code FORM} (see {@link FieldDefinition#effectiveOrigin()}).
 */
public enum SpotOrigin {
    FORM,
    FOUND_BY_BROWNIE,
    ADDED_BY_PERSON
}
