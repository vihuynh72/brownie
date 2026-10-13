package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.core.batch.BatchRowDetail;
import io.github.vihuynh72.brownie.core.batch.BatchService;
import io.github.vihuynh72.brownie.core.batch.BatchRowProblem;
import io.github.vihuynh72.brownie.core.revision.Authorship;
import io.github.vihuynh72.brownie.core.revision.ReviewState;
import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationSeverity;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One row of a batch with everything the detail screen shows: its own
 * state and problems, its document's identity, review, check and compose
 * state, and where it stands in the batch's latest export.
 */
record BatchRowDetailResponse(
        int rowNumber,
        String state,
        Long documentId,
        Long initialRevisionId,
        String documentTitle,
        boolean documentPresent,
        Long currentRevisionId,
        boolean accepted,
        boolean aiComposed,
        boolean suggestionWaiting,
        List<BatchRowResponse.ProblemResponse> problems,
        String composeState,
        Long proposalId,
        CheckResponse check,
        int preCheckBlocking,
        int openQuestions,
        LastExportResponse lastExport,
        OffsetDateTime updatedAt) {

    static BatchRowDetailResponse from(BatchService.EnrichedRow enriched) {
        BatchRowDetail detail = enriched.detail();
        return new BatchRowDetailResponse(
                detail.row().rowNumber(),
                detail.row().state().name(),
                detail.row().documentId(),
                detail.row().initialRevisionId(),
                detail.documentTitle(),
                detail.documentPresent(),
                detail.currentRevisionId(),
                BatchService.isAccepted(detail),
                detail.fieldStates().values().stream().anyMatch(state ->
                        state.authorship() == Authorship.AI_COMPOSED || state.authorship() == Authorship.MIXED),
                detail.proposalWaiting(),
                detail.row().problems().stream().map(BatchRowResponse.ProblemResponse::from).toList(),
                detail.row().composeState().name(),
                detail.row().proposalId(),
                CheckResponse.from(detail),
                enriched.preCheckBlocking(),
                detail.openQuestions(),
                detail.lastExportId() == null
                        ? null
                        : new LastExportResponse(detail.lastExportId(), detail.lastExportOutcome().name()),
                detail.row().updatedAt());
    }

    /** What the newest manifest on the current revision says, counted by severity. */
    record CheckResponse(String state, int blocking, int warnings, int notes, FirstBlocking firstBlocking) {

        static CheckResponse from(BatchRowDetail detail) {
            if (detail.manifestId() == null) {
                return new CheckResponse("NOT_CHECKED", 0, 0, 0, null);
            }
            int blocking = 0;
            int warnings = 0;
            int notes = 0;
            ValidationFinding firstBlocking = null;
            for (ValidationFinding finding : detail.manifestFindings()) {
                switch (finding.severity()) {
                    case BLOCKING -> {
                        blocking++;
                        if (firstBlocking == null) {
                            firstBlocking = finding;
                        }
                    }
                    case WARNING -> warnings++;
                    case INFORMATIONAL -> notes++;
                }
            }
            return new CheckResponse(
                    "CHECKED", blocking, warnings, notes,
                    firstBlocking == null
                            ? null
                            : new FirstBlocking(firstBlocking.code().name(), firstBlocking.fieldId()));
        }
    }

    record FirstBlocking(String code, String fieldId) {
    }

    record LastExportResponse(long exportId, String outcome) {
    }
}
