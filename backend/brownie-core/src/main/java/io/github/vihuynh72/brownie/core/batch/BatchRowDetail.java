package io.github.vihuynh72.brownie.core.batch;

import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldState;
import io.github.vihuynh72.brownie.core.validation.ValidationFinding;

import java.util.List;
import java.util.Map;

/**
 * A batch row together with everything a screen or a step needs to know
 * about its document. The repository fills it set-based -- one query per
 * kind of fact over the whole batch -- so asking for five hundred rows is
 * the same handful of statements as asking for five.
 *
 * <p>{@code currentContent} is null for a row whose document is missing
 * or trashed. {@code initialContentHash} is the hash of the revision the
 * fill step wrote; the accept step compares it with the current hash to
 * let a row that failed to fill, then was fixed by hand, still be
 * accepted. {@code manifestId} and {@code manifestFindings} describe the
 * newest manifest written for the document's current revision, or are
 * empty when no check has run.
 */
public record BatchRowDetail(
        BatchRow row,
        String documentTitle,
        boolean documentPresent,
        Long currentRevisionId,
        DocumentContent currentContent,
        String currentContentHash,
        String initialContentHash,
        Map<FieldItemRef, FieldState> fieldStates,
        Long manifestId,
        List<ValidationFinding> manifestFindings,
        boolean proposalWaiting,
        int openQuestions,
        Long lastExportId,
        BatchExportItemOutcome lastExportOutcome) {
}
