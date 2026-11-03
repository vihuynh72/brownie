package io.github.vihuynh72.brownie.api.generation;

import tools.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.generation.GenerationRun;
import io.github.vihuynh72.brownie.api.generation.GenerationOrchestrationService.GenerationRunView;
import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobEvent;
import io.github.vihuynh72.brownie.core.job.JobEventRepository;
import io.github.vihuynh72.brownie.core.job.JobEventType;
import io.github.vihuynh72.brownie.core.job.JobStage;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.JobTarget;
import io.github.vihuynh72.brownie.core.job.JobType;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationControllerTest {

    @Test
    void aDeadRunCarriesTheLatestSafeReason() {
        GenerationOrchestrationService orchestration = mock(GenerationOrchestrationService.class);
        GenerationRun run = new GenerationRun(
                91L, 42L, 7L, 3L, 5L, 4L, 6L, 81L,
                "bundle-hash", "model-x", "prompt-v", 7L, OffsetDateTime.parse("2026-11-02T02:00:00Z"));
        Job deadJob = new Job(81L, 42L, 7L, new JobType("generation.extraction"), new JobTarget("document", 7L, 1L),
                new JobStage("extracting"), null, JobState.DEAD, 2, null, null, null, 0L, null,
                OffsetDateTime.parse("2026-09-11T17:00:00Z"), OffsetDateTime.parse("2026-09-11T17:30:00Z"));
        when(orchestration.listRuns(42L, 7L, 7L)).thenReturn(List.of(new GenerationRunView(run, deadJob, null)));
        GenerationController controller = new GenerationController(
                orchestration,
                new FixedJobEventRepository(Optional.of(latestReason())),
                new CanonicalRequestHasher(new ObjectMapper()),
                new WorkspaceAuthorizationService(new OwnerWorkspaceRepository()),
                new FixedIdentityRepository(new UserIdentity(7L, "https://issuer.example", "subject-7", null, null, null, null, null, null, null, null)));

        List<GenerationController.GenerationRunResponse> responses =
                controller.listRuns(42L, 7L, oidcUserWith("https://issuer.example", "subject-7"));

        assertThat(responses.get(0).job().failureMessage())
                .isEqualTo("Today's free allowance is used up. Ask for an invitation to keep going.");
    }

    private static JobEvent latestReason() {
        return new JobEvent(1L, 42L, 81L, new JobTarget("document", 7L, 1L), new JobStage("extracting"), 4L,
                JobEventType.COMPLETED, JobState.DEAD,
                "Today's free allowance is used up. Ask for an invitation to keep going.", null, null,
                OffsetDateTime.parse("2026-09-11T17:30:00Z"));
    }

    private static OidcUser oidcUserWith(String issuer, String subject) {
        OidcIdToken idToken = new OidcIdToken(
                "token", Instant.now(), Instant.now().plusSeconds(60), Map.of("iss", issuer, "sub", subject));
        return new DefaultOidcUser(List.of(), idToken);
    }

    private record FixedJobEventRepository(Optional<JobEvent> latest) implements JobEventRepository {
        @Override
        public List<JobEvent> findAfter(long workspaceId, long actorUserId, long afterEventId, int limit) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<JobEvent> latestForJob(long workspaceId, long actorUserId, long jobId) {
            return latest;
        }

        @Override
        public boolean exists(long workspaceId, long actorUserId, long eventId) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }

    private record FixedIdentityRepository(UserIdentity identity) implements UserIdentityRepository {
        @Override
        public UserIdentity recordLogin(String issuer, String subject, String email, String displayName) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public UserIdentity recordName(long userId, String givenName, String familyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserIdentity prefillNames(long userId, String givenName, String familyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject) {
            return Optional.of(identity);
        }

        @Override
        public void recordInvitationRequest(long userId, String note) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public boolean hasInvitationRequest(long userId) {
            return false;
        }
    }

    private static final class OwnerWorkspaceRepository implements WorkspaceRepository {
        @Override
        public Workspace ensurePersonalWorkspace(long ownerUserId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public List<WorkspaceMember> findMembershipsForUser(long userId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<WorkspaceRole> findRole(long workspaceId, long userId) {
            return Optional.of(WorkspaceRole.OWNER);
        }

        @Override
        public boolean setOpenAllowance(long workspaceId, long userId, boolean open) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<Boolean> findOpenAllowance(long userId) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }
}
