package io.github.vihuynh72.brownie.core.revision;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** One {@link PatchFieldStatus} per field a proposal touches, produced by {@link PatchComparator#compare}. */
public record PatchComparison(Map<String, PatchFieldStatus> fieldStatuses) {

    public PatchComparison {
        fieldStatuses = Map.copyOf(new LinkedHashMap<>(fieldStatuses));
    }

    /** Field IDs safe to apply -- {@link PatchFieldStatus#CLEAN} only. */
    public Set<String> applicableFields() {
        Set<String> clean = new LinkedHashSet<>();
        fieldStatuses.forEach((fieldId, status) -> {
            if (status == PatchFieldStatus.CLEAN) {
                clean.add(fieldId);
            }
        });
        return clean;
    }

    public boolean hasBlockedFields() {
        return fieldStatuses.values().stream().anyMatch(status -> status != PatchFieldStatus.CLEAN);
    }
}
