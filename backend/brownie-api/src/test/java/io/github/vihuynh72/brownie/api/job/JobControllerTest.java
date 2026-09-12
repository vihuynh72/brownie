package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CancellationCommand;
import io.github.vihuynh72.brownie.core.job.CommandReceipt;
import io.github.vihuynh72.brownie.core.job.CommandReceiptStatus;
import io.github.vihuynh72.brownie.core.job.EnqueueJobCommand;
import io.github.vihuynh72.brownie.core.job.Job;
import io.github.vihuynh72.brownie.core.job.JobCommandRepository;
import io.github.vihuynh72.brownie.core.job.JobCommandType;
import io.github.vihuynh72.brownie.core.job.ResumeJobCommand;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobControllerTest {

    @Test
    void resumeEndpointBuildsAnIdempotentResumeCommand() {
        CommandReceipt receipt = new CommandReceipt(
                UUID.fromString("f9a02ca1-af87-47a6-85d0-2f5705f6ec2c"),
                42L,
                7L,
                JobCommandType.REQUEST_RESUME,
                81L,
                io.github.vihuynh72.brownie.core.job.CanonicalRequestHash.sha256OfCanonicalText("resume"),
                CommandReceiptStatus.ACCEPTED,
                OffsetDateTime.parse("2026-09-11T17:00:00Z"));
        CapturingJobCommandRepository jobRepository = new CapturingJobCommandRepository(receipt);
        JobController controller = new JobController(
                jobRepository,
                new CanonicalRequestHasher(new ObjectMapper()),
                new WorkspaceAuthorizationService(new OwnerWorkspaceRepository()),
                new FixedIdentityRepository(new UserIdentity(7L, "https://issuer.example", "subject-7", null, null, null, null, null)));

        JobController.CommandReceiptResponse response = controller.requestResume(
                42L, 81L, "resume-key", oidcUserWith("https://issuer.example", "subject-7"));

        assertThat(jobRepository.resumeCommand).isNotNull();
        assertThat(jobRepository.resumeCommand.idempotencyKey().value()).isEqualTo("resume-key");
        assertThat(jobRepository.resumeCommand.jobId()).isEqualTo(81L);
        assertThat(response.commandId()).isEqualTo(receipt.commandId().toString());
        assertThat(response.operation()).isEqualTo("job.request-resume");
        assertThat(response.status()).isEqualTo("ACCEPTED");
    }

    private static OidcUser oidcUserWith(String issuer, String subject) {
        OidcIdToken idToken = new OidcIdToken(
                "test-token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of(IdTokenClaimNames.ISS, issuer, IdTokenClaimNames.SUB, subject));
        return new DefaultOidcUser(List.of(), idToken);
    }

    private static final class CapturingJobCommandRepository implements JobCommandRepository {

        private final CommandReceipt receipt;
        private ResumeJobCommand resumeCommand;

        private CapturingJobCommandRepository(CommandReceipt receipt) {
            this.receipt = receipt;
        }

        @Override
        public CommandReceipt enqueue(long workspaceId, long actorUserId, EnqueueJobCommand command) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public CommandReceipt requestCancellation(long workspaceId, long actorUserId, CancellationCommand command) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public CommandReceipt requestResume(long workspaceId, long actorUserId, ResumeJobCommand command) {
            resumeCommand = command;
            return receipt;
        }

        @Override
        public Optional<Job> find(long workspaceId, long actorUserId, long jobId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<CommandReceipt> findReceipt(long workspaceId, long actorUserId, UUID commandId) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }

    private record FixedIdentityRepository(UserIdentity identity) implements UserIdentityRepository {

        @Override
        public UserIdentity recordLogin(String issuer, String subject, String email, String displayName) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject) {
            return Optional.of(identity);
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
    }
}
