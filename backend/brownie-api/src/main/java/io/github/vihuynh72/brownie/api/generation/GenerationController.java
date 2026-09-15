package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.job.JobOutputArtifactRepository;
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
 * Starts a document's own grounded fact extraction and reads back its
 * published result once the trusted worker finishes it. Deliberately
 * thin: ordinary job status/stage polling stays on the already-generic
 * {@code GET .../jobs/{jobId}} route ({@code JobController}); this
 * controller's own second route exists only for the one thing that route
 * cannot answer -- which artifact, if any, a finished job actually
 * produced -- so a caller can then download it through the existing
 * generic {@code /uploads/{artifactId}/download} route.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/generations")
class GenerationController {

    private final GenerationOrchestrationService generationOrchestrationService;
    private final JobOutputArtifactRepository jobOutputArtifactRepository;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    GenerationController(
            GenerationOrchestrationService generationOrchestrationService,
            JobOutputArtifactRepository jobOutputArtifactRepository,
            CanonicalRequestHasher canonicalRequestHasher,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.generationOrchestrationService = generationOrchestrationService;
        this.jobOutputArtifactRepository = jobOutputArtifactRepository;
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

    /** 404 until the worker publishes this job's own extraction result. */
    @GetMapping("/{jobId}/result")
    ExtractionResultResponse findResult(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long jobId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        Long artifactId = jobOutputArtifactRepository
                .findArtifactId(workspaceId, userId, jobId, GenerationOrchestrationService.EXTRACTION_RESULT_OUTPUT_KIND)
                .orElseThrow(() -> new GenerationResultNotFoundException(jobId));
        return new ExtractionResultResponse(artifactId);
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
     * silently" rule for every other patch proposal.
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
        PatchProposal proposal = generationOrchestrationService.applyResultAsPatchProposal(workspaceId, userId, documentId, jobId);
        return PatchProposalResponse.from(proposal);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no recorded identity for issuer/subject " + issuer + "/" + subject))
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

    record ProposedFieldResponse(String type, String value, List<Long> evidenceSpanIds) {
        static ProposedFieldResponse from(FieldValue value, List<Long> evidenceSpanIds) {
            return switch (value) {
                case FieldValue.TextValue(String text) -> new ProposedFieldResponse("TEXT", text, evidenceSpanIds);
                case FieldValue.DateValue(java.time.LocalDate date) -> new ProposedFieldResponse("DATE", date.toString(), evidenceSpanIds);
                case FieldValue.RepeatedTextValue ignored -> throw new IllegalStateException("A generation-proposed field is never repeated.");
                case FieldValue.RepeatedDateValue ignored -> throw new IllegalStateException("A generation-proposed field is never repeated.");
            };
        }
    }

    record PatchProposalResponse(
            long id,
            long documentId,
            long baseRevisionId,
            Map<String, ProposedFieldResponse> proposedValues,
            String status,
            OffsetDateTime createdAt) {
        static PatchProposalResponse from(PatchProposal proposal) {
            Map<String, ProposedFieldResponse> fields = new java.util.LinkedHashMap<>();
            proposal.proposedValues().forEach((fieldId, value) -> fields.put(
                    fieldId,
                    ProposedFieldResponse.from(value, proposal.proposedEvidence().getOrDefault(fieldId, List.of()))));
            return new PatchProposalResponse(
                    proposal.id(), proposal.documentId(), proposal.baseRevisionId(), fields, proposal.status().name(), proposal.createdAt());
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
