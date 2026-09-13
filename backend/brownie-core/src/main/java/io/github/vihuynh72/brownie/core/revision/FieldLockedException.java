package io.github.vihuynh72.brownie.core.revision;

/**
 * A direct edit named a field or item whose current {@link
 * LockState#EXPLICITLY_LOCKED} state forbids it. {@link
 * LockState#PRESERVE_ON_REGENERATION} does not throw this -- it protects
 * a field only from automated regeneration (see {@link PatchComparator}),
 * never from a person's own direct edit.
 */
public class FieldLockedException extends RuntimeException {

    private final String fieldId;

    public FieldLockedException(String fieldId) {
        super("Field '" + fieldId + "' is explicitly locked and cannot be edited directly.");
        this.fieldId = fieldId;
    }

    public String fieldId() {
        return fieldId;
    }
}
