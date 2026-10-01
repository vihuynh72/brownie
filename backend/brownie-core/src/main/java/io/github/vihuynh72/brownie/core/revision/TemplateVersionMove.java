package io.github.vihuynh72.brownie.core.revision;

import java.util.List;
import java.util.Objects;

/**
 * What moving a document to another version of its template produced: the
 * revision it appended on the new version, the version the document was on
 * before, and the fields whose values were dropped because the new version
 * has no fill spot for them, sorted by field ID.
 */
public record TemplateVersionMove(DocumentMutationResult mutation, long previousTemplateVersionId, List<String> droppedFieldIds) {

    public TemplateVersionMove {
        Objects.requireNonNull(mutation, "mutation");
        droppedFieldIds = List.copyOf(droppedFieldIds);
    }
}
