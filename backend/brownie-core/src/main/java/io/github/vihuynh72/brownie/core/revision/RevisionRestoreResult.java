package io.github.vihuynh72.brownie.core.revision;

import java.util.List;
import java.util.Objects;

/**
 * What restoring an earlier revision produced: the new revision it appended,
 * the fields that kept their current value instead because a person had
 * locked them, and the fields that were dropped because the restored
 * revision's template version has no fill spot for them, each sorted by
 * field ID. A replay of the same request answers with the same revision and
 * the same lists.
 */
public record RevisionRestoreResult(DocumentMutationResult mutation, List<String> keptLockedFieldIds, List<String> droppedFieldIds) {

    public RevisionRestoreResult {
        Objects.requireNonNull(mutation, "mutation");
        keptLockedFieldIds = List.copyOf(keptLockedFieldIds);
        droppedFieldIds = List.copyOf(droppedFieldIds);
    }
}
