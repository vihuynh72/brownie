package io.github.vihuynh72.brownie.core.revision;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Addresses one dimension-bearing unit within a revision's content: a
 * whole scalar field ({@code itemIndex == null}), or one row of a
 * repeated field's list ({@code itemIndex} a 0-based position). Every
 * item within one repeated field shares the same {@code fieldId} but has
 * its own independent {@link FieldState} -- an owner and a due date on
 * the same action item are already independent claims (see {@code
 * RepeatedItemCandidate}), and one item's own review or lock state must
 * never bleed into another's.
 */
public record FieldItemRef(String fieldId, Integer itemIndex) {

    public FieldItemRef {
        if (fieldId == null || fieldId.isBlank()) {
            throw new IllegalArgumentException("fieldId must not be blank.");
        }
        if (itemIndex != null && itemIndex < 0) {
            throw new IllegalArgumentException("itemIndex must not be negative when present.");
        }
    }

    /** A whole scalar field, with no item index. */
    public static FieldItemRef scalar(String fieldId) {
        return new FieldItemRef(fieldId, null);
    }

    /** One 0-based row within a repeated field's list. */
    public static FieldItemRef item(String fieldId, int itemIndex) {
        return new FieldItemRef(fieldId, itemIndex);
    }

    /** Every ref a field's own current value implies: one scalar ref, or one ref per item in a repeated value's list, in order. */
    public static List<FieldItemRef> allFor(String fieldId, FieldValue value) {
        return switch (value) {
            case FieldValue.TextValue ignored -> List.of(scalar(fieldId));
            case FieldValue.DateValue ignored -> List.of(scalar(fieldId));
            case FieldValue.RepeatedTextValue(List<String> values) -> items(fieldId, values.size());
            case FieldValue.RepeatedDateValue(List<LocalDate> values) -> items(fieldId, values.size());
        };
    }

    private static List<FieldItemRef> items(String fieldId, int itemCount) {
        List<FieldItemRef> refs = new ArrayList<>(itemCount);
        for (int index = 0; index < itemCount; index++) {
            refs.add(item(fieldId, index));
        }
        return refs;
    }
}
