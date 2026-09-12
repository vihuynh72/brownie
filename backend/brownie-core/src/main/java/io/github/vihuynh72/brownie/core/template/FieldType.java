package io.github.vihuynh72.brownie.core.template;

/**
 * A field's own value type, independent of its {@link FieldCardinality} or
 * {@link FieldBindingTarget}. Only the two types the meeting-minutes schema
 * itself requires today (a title, a meeting date) -- more arrive once a
 * template actually needs them, the same minimal-enum precedent {@code
 * WorkspaceStatus} and {@code ArtifactStatus} already set in this codebase.
 */
public enum FieldType {
    TEXT,
    DATE
}
