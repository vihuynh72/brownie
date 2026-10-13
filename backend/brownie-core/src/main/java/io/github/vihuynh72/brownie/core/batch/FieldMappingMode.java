package io.github.vihuynh72.brownie.core.batch;

/**
 * How a column fills its field: {@code VALUE} takes the cell as read;
 * {@code COMPOSE} asks a model to write the value from the row's data when
 * the deployment offers it, leaving the document's value a person's accepted
 * proposal rather than a cell.
 */
public enum FieldMappingMode {
    VALUE,
    COMPOSE
}
