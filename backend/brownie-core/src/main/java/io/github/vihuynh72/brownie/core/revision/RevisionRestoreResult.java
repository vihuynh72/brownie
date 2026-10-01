package io.github.vihuynh72.brownie.core.revision;

import java.util.List;
import java.util.Objects;

/**
 * What restoring an earlier revision produced: the new revision it appended,
 * and the fields that kept their current value instead because a person had
 * locked them, sorted by field ID. A replay of the same request answers with
 * the same revision and the same list.
 */
public record RevisionRestoreResult(DocumentMutationResult mutation, List<String> keptLockedFieldIds) {

    public RevisionRestoreResult {
        Objects.requireNonNull(mutation, "mutation");
        keptLockedFieldIds = List.copyOf(keptLockedFieldIds);
    }
}
