package io.github.vihuynh72.brownie.core.template;

/**
 * Where a Word fill spot's named control came from, so removing the spot
 * can undo exactly what was done to the file: an {@code ORIGINAL} control
 * was in the file already and is left as it is; one {@code
 * TAGGED_BY_BROWNIE} was in the file without a name, and only the name
 * Brownie gave it is taken off; one {@code INSERTED_BY_BROWNIE} did not
 * exist, and is unwrapped so the text it held goes back where it was. A
 * {@link FieldDefinition} with none reads as {@code ORIGINAL}; a spot on
 * any other kind of document never has one.
 */
public enum DocxControlOrigin {
    ORIGINAL,
    TAGGED_BY_BROWNIE,
    INSERTED_BY_BROWNIE
}
