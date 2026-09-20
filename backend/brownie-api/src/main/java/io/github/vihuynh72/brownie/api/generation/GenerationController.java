package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.api.job.JobResponse;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.generation.GenerationRun;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.question.Question;
import io.github.vihuynh72.brownie.core.question.QuestionCandidateOption;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.PatchProposal;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Starts a document's own grounded fact extraction, lists the runs it
 * has started, and reads back a run's published result once the trusted
 * worker finishes it. Deliberately thin: ordinary job status/stage
 * polling stays on the already-generic {@code GET .../jobs/{jobId}} route
 * ({@code JobController}); the result route exists only for the one
 * thing that route cannot answer -- which artifact, if any, a finished
 * job actually produced -- so a caller can then download it through the
 * existing generic {@code /uploads/{artifactId}/download} route, and the
 * list route is how a reloaded page finds a run still in flight.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/generations")
class GenerationController {

    private final GenerationOrchestrationService generationOrchestrationService;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    GenerationController(
            GenerationOrchestrationService generationOrchestrationService,
            CanonicalRequestHasher canonicalRequestHasher,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.generationOrchestrationService = generationOrchestrationService;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandReceiptResponse startExtraction(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody StartExtractionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        if (request.sourceArtifactId() <= 0) {
            throw new GenerationRequestValidationException("sourceArtifactId must be positive.");
        }
        CommandReceipt receipt = generationOrchestrationService.startExtraction(
                workspaceId,
                userId,
                documentId,
                request.sourceArtifactId(),
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new StartExtractionHashInput(
                        "generation.start-extraction", workspaceId, documentId, request)));
        return CommandReceiptResponse.from(receipt);
    }

    /** Every run started for this document, most recent first -- see {@code GenerationOrchestrationService#listRuns}. */
    @GetMapping
    List<GenerationRunResponse> listRuns(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return generationOrchestrationService.listRuns(workspaceId, userId, documentId).stream()
                .map(GenerationRunResponse::from)
                .toList();
    }

    /** 404 until the worker publishes this job's own extraction result. */
    @GetMapping("/{jobId}/result")
    ExtractionResultResponse findResult(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long jobId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return new ExtractionResultResponse(
                generationOrchestrationService.requireResultArtifactId(workspaceId, userId, documentId, jobId));
    }

    /** Empty until the worker leaves this job {@code WAITING_FOR_INPUT} with at least one detected question -- poll {@code GET .../jobs/{jobId}} for that state first. */
    @GetMapping("/{jobId}/questions")
    List<QuestionResponse> findQuestions(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long jobId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return generationOrchestrationService.openQuestions(workspaceId, userId, documentId, jobId).stream()
                .map(QuestionResponse::from)
                .toList();
    }

    /** Freezes every currently answered question and asks the worker to pick this job back up -- see {@code GenerationOrchestrationService#resumeAfterQuestions}. */
    @PostMapping("/{jobId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandReceiptResponse resume(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long jobId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        CommandReceipt receipt = generationOrchestrationService.resumeAfterQuestions(
                workspaceId,
                userId,
                documentId,
                jobId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new ResumeHashInput("generation.resume", workspaceId, documentId, jobId)));
        return CommandReceiptResponse.from(receipt);
    }

    /**
     * Turns this job's own published result into a real, reviewable patch
     * proposal against the document's current revision -- see {@code
     * GenerationOrchestrationService#applyResultAsPatchProposal}. Accepting
     * it is a separate, explicit step ({@code PatchProposalController}),
     * matching this codebase's own "AI output is proposed, never applied
     * silently" rule for every other patch proposal. The response also
     * names any repeated row (action item) the result contained that the
     * proposal could not carry whole, so nothing the model found is ever
     * lost without the person being told.
     */
    @PostMapping("/{jobId}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    PatchProposalResponse apply(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long jobId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        GenerationApplyOutcome outcome = generationOrchestrationService.applyResultAsPatchProposal(workspaceId, userId, documentId, jobId);
        return PatchProposalResponse.from(outcome);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    private static IdempotencyKey requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new GenerationRequestValidationException("Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        return new IdempotencyKey(value);
    }

    private record StartExtractionHashInput(String operation, long workspaceId, long documentId, StartExtractionRequest request) {
    }

    private record ResumeHashInput(String operation, long workspaceId, long documentId, long jobId) {
    }

    record StartExtractionRequest(long sourceArtifactId) {
    }

    record ExtractionResultResponse(long artifactId) {
    }

    record GenerationRunResponse(
            long id,
            long jobId,
            long documentId,
            long baseRevisionId,
            long sourceSnapshotId,
            long sourceArtifactId,
            String modelName,
            String promptVersion,
            OffsetDateTime createdAt,
            JobResponse job,
            Long resultArtifactId) {
        static GenerationRunResponse from(GenerationOrchestrationService.GenerationRunView view) {
            GenerationRun run = view.run();
            return new GenerationRunResponse(
                    run.id(),
                    run.jobId(),
                    run.documentId(),
                    run.baseRevisionId(),
                    run.sourceSnapshotId(),
                    run.sourceArtifactId(),
                    run.modelName(),
                    run.promptVersion(),
                    run.createdAt(),
                    JobResponse.from(view.job()),
                    view.resultArtifactId());
        }
    }

    record QuestionCandidateOptionResponse(String value, List<Long> evidenceSpanIds) {
        static QuestionCandidateOptionResponse from(QuestionCandidateOption candidate) {
            return new QuestionCandidateOptionResponse(candidate.value(), candidate.evidenceSpanIds());
        }
    }

    record QuestionResponse(
            long id,
            String fieldId,
            String reason,
            List<QuestionCandidateOptionResponse> candidates,
            String status,
            String answerValue,
            OffsetDateTime createdAt) {
        static QuestionResponse from(Question question) {
            return new QuestionResponse(
                    question.id(),
                    question.fieldId(),
                    question.reason().name(),
                    question.candidates().stream().map(QuestionCandidateOptionResponse::from).toList(),
                    question.status().name(),
                    question.answerValue(),
                    question.createdAt());
        }
    }

    /** Exactly one of {@code value} (SCALAR) or {@code values} (REPEATED) is set, the same shape {@code DocumentController.FieldValueResponse} already uses for a stored field. */
    record ProposedFieldResponse(String type, String cardinality, String value, List<String> values, List<Long> evidenceSpanIds) {
        static ProposedFieldResponse from(FieldValue value, List<Long> evidenceSpanIds) {
            return switch (value) {
                case FieldValue.TextValue(String text) -> new ProposedFieldResponse("TEXT", "SCALAR", text, null, evidenceSpanIds);
                case FieldValue.DateValue(java.time.LocalDate date) ->
                        new ProposedFieldResponse("DATE", "SCALAR", date.toString(), null, evidenceSpanIds);
                case FieldValue.RepeatedTextValue(List<String> texts) ->
                        new ProposedFieldResponse("TEXT", "REPEATED", null, texts, evidenceSpanIds);
                case FieldValue.RepeatedDateValue(List<java.time.LocalDate> dates) -> new ProposedFieldResponse(
                        "DATE", "REPEATED", null, dates.stream().map(java.time.LocalDate::toString).toList(), evidenceSpanIds);
            };
        }
    }

    /** A repeated row the result contained but the proposal could not carry whole -- see {@link GenerationApplyOutcome.SkippedRepeatedItem}. */
    record SkippedRepeatedItemResponse(int itemIndex, List<String> unresolvedFieldIds, String description) {
        static SkippedRepeatedItemResponse from(GenerationApplyOutcome.SkippedRepeatedItem item) {
            return new SkippedRepeatedItemResponse(item.itemIndex(), item.unresolvedFieldIds(), item.description());
        }
    }

    record PatchProposalResponse(
            long id,
            long documentId,
            long baseRevisionId,
            Map<String, ProposedFieldResponse> proposedValues,
            String status,
            OffsetDateTime createdAt,
            int proposedRepeatedItemCount,
            List<SkippedRepeatedItemResponse> skippedRepeatedItems) {
        /** A proposal that carries no repeated rows of its own (the composer's), in the same shape the generation route returns. */
        static PatchProposalResponse fromProposal(PatchProposal proposal) {
            return from(new GenerationApplyOutcome(proposal, 0, List.of()));
        }

        static PatchProposalResponse from(GenerationApplyOutcome outcome) {
            PatchProposal proposal = outcome.proposal();
            Map<String, ProposedFieldResponse> fields = new java.util.LinkedHashMap<>();
            proposal.proposedValues().forEach((fieldId, value) -> fields.put(
                    fieldId,
                    ProposedFieldResponse.from(value, proposal.proposedEvidence().getOrDefault(fieldId, List.of()))));
            return new PatchProposalResponse(
                    proposal.id(),
                    proposal.documentId(),
                    proposal.baseRevisionId(),
                    fields,
                    proposal.status().name(),
                    proposal.createdAt(),
                    outcome.proposedRepeatedItemCount(),
                    outcome.skippedRepeatedItems().stream().map(SkippedRepeatedItemResponse::from).toList());
        }
    }

    record CommandReceiptResponse(String commandId, long jobId, String operation, String status, OffsetDateTime acceptedAt) {
        static CommandReceiptResponse from(CommandReceipt receipt) {
            return new CommandReceiptResponse(
                    receipt.commandId().toString(), receipt.jobId(), receipt.commandType().operation(),
                    receipt.status().name(), receipt.acceptedAt());
        }
    }
}
