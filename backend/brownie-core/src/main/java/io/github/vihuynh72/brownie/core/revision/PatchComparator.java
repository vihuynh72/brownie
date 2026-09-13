package io.github.vihuynh72.brownie.core.revision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, stateless three-way comparison for one regeneration proposal:
 * base (the revision the proposal was generated from), current (the
 * document's real current revision, which may have moved since), and the
 * proposal's own proposed values. No repository, no model call, directly
 * unit-testable with hand-built revisions -- the same shape {@code
 * QuestionDetectionService} already establishes for a comparable
 * comparison.
 */
public final class PatchComparator {

    private PatchComparator() {
    }

    public static PatchComparison compare(DocumentRevision base, DocumentRevision current, Map<String, FieldValue> proposedValues) {
        Map<String, PatchFieldStatus> statuses = new LinkedHashMap<>();
        proposedValues.forEach((fieldId, proposedValue) -> statuses.put(fieldId, statusOf(base, current, fieldId)));
        return new PatchComparison(statuses);
    }

    private static PatchFieldStatus statusOf(DocumentRevision base, DocumentRevision current, String fieldId) {
        if (isLocked(current, fieldId)) {
            return PatchFieldStatus.LOCKED;
        }
        FieldValue baseValue = base.content().fields().get(fieldId);
        FieldValue currentValue = current.content().fields().get(fieldId);
        return Objects.equals(baseValue, currentValue) ? PatchFieldStatus.CLEAN : PatchFieldStatus.CONFLICT;
    }

    /** A field is locked if its whole scalar value, or any one item of its repeated value, is not {@link LockState#EDITABLE} in the current revision. */
    private static boolean isLocked(DocumentRevision current, String fieldId) {
        FieldValue currentValue = current.content().fields().get(fieldId);
        if (currentValue == null) {
            return false;
        }
        for (FieldItemRef ref : FieldItemRef.allFor(fieldId, currentValue)) {
            FieldState state = current.fieldStates().get(ref);
            if (state != null && state.lock() != LockState.EDITABLE) {
                return true;
            }
        }
        return false;
    }
}
