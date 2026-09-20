package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.InvalidJobTransitionException;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobNotFoundException;
import io.github.vihuynh72.brownie.core.job.JobRetryOutcome;
import io.github.vihuynh72.brownie.core.job.JobRetryRepository;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.JobTargetStaleException;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts a job that gave up again. It takes no idempotency key because it
 * is idempotent by what it does: asking twice finds the job already queued
 * the second time and answers with it as it is, and nothing is started
 * twice. The answer is the job itself, so a client that was polling a dead
 * job simply goes on polling the same one.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/jobs")
class JobRetryController {

    private final JobRetryRepository jobRetryRepository;
    private final JobCommandRepository jobCommandRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    JobRetryController(
            JobRetryRepository jobRetryRepository,
            JobCommandRepository jobCommandRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.jobRetryRepository = jobRetryRepository;
        this.jobCommandRepository = jobCommandRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping("/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobResponse retry(
            @PathVariable long workspaceId,
            @PathVariable long jobId,
            @AuthenticationPrincipal OidcUser principal) {
        if (jobId <= 0) {
            throw new JobRequestValidationException("jobId must be positive.");
        }
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);

        JobRetryOutcome outcome = jobRetryRepository.retry(workspaceId, userId, jobId);
        Job job = jobCommandRepository.find(workspaceId, userId, jobId).orElseThrow(() -> new JobNotFoundException(jobId));
        return switch (outcome) {
            case RETRIED, ALREADY_ACTIVE -> JobResponse.from(job);
            case NOT_RETRYABLE -> throw new InvalidJobTransitionException(job.state(), JobState.QUEUED);
            case STALE_TARGET -> throw new JobTargetStaleException(jobId);
            case NOT_FOUND -> throw new JobNotFoundException(jobId);
        };
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }
}
