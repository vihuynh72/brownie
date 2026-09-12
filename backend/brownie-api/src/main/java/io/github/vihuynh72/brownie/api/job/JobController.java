package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CancellationCommand;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.job.ResumeJobCommand;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/** Reads durable job state and accepts the one supported job mutation. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/jobs")
class JobController {

    private final JobCommandRepository jobCommandRepository;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    JobController(
            JobCommandRepository jobCommandRepository,
            CanonicalRequestHasher canonicalRequestHasher,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.jobCommandRepository = jobCommandRepository;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping("/{jobId}")
    JobResponse find(
            @PathVariable long workspaceId,
            @PathVariable long jobId,
            @AuthenticationPrincipal OidcUser principal) {
        if (jobId <= 0) {
            throw new JobRequestValidationException("jobId must be positive.");
        }
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        Job job = jobCommandRepository.find(workspaceId, userId, jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        return JobResponse.from(job);
    }

    @PostMapping("/{jobId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandReceiptResponse requestCancellation(
            @PathVariable long workspaceId,
            @PathVariable long jobId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        if (jobId <= 0) {
            throw new JobRequestValidationException("jobId must be positive.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new JobRequestValidationException("Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        CommandReceipt receipt = jobCommandRepository.requestCancellation(
                workspaceId,
                userId,
                new CancellationCommand(
                        new IdempotencyKey(idempotencyKey),
                        canonicalRequestHasher.hash(new CancellationHashInput("job.request-cancellation", workspaceId, jobId)),
                        jobId));
        return CommandReceiptResponse.from(receipt);
    }

    @PostMapping("/{jobId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CommandReceiptResponse requestResume(
            @PathVariable long workspaceId,
            @PathVariable long jobId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        if (jobId <= 0) {
            throw new JobRequestValidationException("jobId must be positive.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new JobRequestValidationException("Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        CommandReceipt receipt = jobCommandRepository.requestResume(
                workspaceId,
                userId,
                new ResumeJobCommand(
                        new IdempotencyKey(idempotencyKey),
                        canonicalRequestHasher.hash(new ResumeHashInput("job.request-resume", workspaceId, jobId)),
                        jobId));
        return CommandReceiptResponse.from(receipt);
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

    private record CancellationHashInput(String operation, long workspaceId, long jobId) {
    }

    private record ResumeHashInput(String operation, long workspaceId, long jobId) {
    }

    record CommandReceiptResponse(
            String commandId,
            long jobId,
            String operation,
            String status,
            OffsetDateTime acceptedAt) {

        static CommandReceiptResponse from(CommandReceipt receipt) {
            return new CommandReceiptResponse(
                    receipt.commandId().toString(),
                    receipt.jobId(),
                    receipt.commandType().operation(),
                    receipt.status().name(),
                    receipt.acceptedAt());
        }
    }

    record JobResponse(
            long id,
            String type,
            String resourceType,
            long resourceId,
            long resourceVersion,
            String stage,
            String state,
            int attemptCount,
            OffsetDateTime availableAt,
            OffsetDateTime deadlineAt,
            OffsetDateTime cancellationRequestedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static JobResponse from(Job job) {
            return new JobResponse(
                    job.id(),
                    job.type().value(),
                    job.target().resourceType(),
                    job.target().resourceId(),
                    job.target().resourceVersion(),
                    job.stage().value(),
                    job.state().name(),
                    job.attemptCount(),
                    job.availableAt(),
                    job.deadlineAt(),
                    job.cancellationRequestedAt(),
                    job.createdAt(),
                    job.updatedAt());
        }
    }
}
