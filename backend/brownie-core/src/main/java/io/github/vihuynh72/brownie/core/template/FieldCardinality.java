package io.github.vihuynh72.brownie.core.template;

/** SCALAR binds one content control to one value. REPEATED binds a whole region whose row/paragraph count varies per document; cloning is a renderer concern, not this field's own concern. */
public enum FieldCardinality {
    SCALAR,
    REPEATED
}
