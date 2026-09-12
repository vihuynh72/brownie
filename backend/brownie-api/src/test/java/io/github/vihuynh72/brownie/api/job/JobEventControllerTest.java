package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.JobEvent;
import io.github.vihuynh72.brownie.core.job.JobEventRepository;
import io.github.vihuynh72.brownie.core.job.JobEventType;
import io.github.vihuynh72.brownie.core.job.JobStage;
import io.github.vihuynh72.brownie.core.job.JobState;
import io.github.vihuynh72.brownie.core.job.JobTarget;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobEventControllerTest {

    @Test
    void pollReturnsPersistedEventsInOrderAndAdvancesTheReconnectCursor() {
        CapturingJobEventRepository events = new CapturingJobEventRepository(true, List.of(event(18L), event(21L)));
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor()) {
            JobEventController controller = controller(events, true, executor);

            JobEventController.EventBatchResponse response = controller.poll(
                    42L, 12L, 2, oidcUserWith("https://issuer.example", "subject-7"));

            assertThat(response.resyncRequired()).isFalse();
            assertThat(response.nextEventId()).isEqualTo(21L);
            assertThat(response.events()).extracting(JobEventController.EventResponse::eventId)
                    .containsExactly(18L, 21L);
            assertThat(events.findAfterArguments).containsExactly(42L, 7L, 12L, 2L);
        }
    }

    @Test
    void pollRequiresAuthoritativeResyncWhenTheReconnectCursorIsGone() {
        CapturingJobEventRepository events = new CapturingJobEventRepository(false, List.of(event(21L)));
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor()) {
            JobEventController controller = controller(events, true, executor);

            JobEventController.EventBatchResponse response = controller.poll(
                    42L, 18L, 10, oidcUserWith("https://issuer.example", "subject-7"));

            assertThat(response.resyncRequired()).isTrue();
            assertThat(response.nextEventId()).isEqualTo(18L);
            assertThat(response.events()).isEmpty();
            assertThat(events.findAfterArguments).isEmpty();
            assertThat(events.existsArguments).containsExactly(42L, 7L, 18L);
        }
    }

    @Test
    void pollRejectsInvalidCursorAndPageBoundsBeforeReadingEvents() {
        CapturingJobEventRepository events = new CapturingJobEventRepository(true, List.of());
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor()) {
            JobEventController controller = controller(events, true, executor);
            OidcUser principal = oidcUserWith("https://issuer.example", "subject-7");

            assertThatThrownBy(() -> controller.poll(42L, -1L, 1, principal))
                    .isInstanceOf(JobRequestValidationException.class)
                    .hasMessage("afterEventId must not be negative.");
            assertThatThrownBy(() -> controller.poll(42L, 0L, 101, principal))
                    .isInstanceOf(JobRequestValidationException.class)
                    .hasMessage("limit must be between 1 and 100.");
            assertThat(events.existsArguments).isEmpty();
            assertThat(events.findAfterArguments).isEmpty();
        }
    }

    @Test
    void pollRejectsANonMemberBeforeExposingEvents() {
        CapturingJobEventRepository events = new CapturingJobEventRepository(true, List.of(event(21L)));
        try (ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor()) {
            JobEventController controller = controller(events, false, executor);

            assertThatThrownBy(() -> controller.poll(
                    42L, 0L, 10, oidcUserWith("https://issuer.example", "subject-7")))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("Not a member of this workspace.");
            assertThat(events.existsArguments).isEmpty();
            assertThat(events.findAfterArguments).isEmpty();
        }
    }

    private static JobEventController controller(
            CapturingJobEventRepository events,
            boolean hasWorkspaceAccess,
            ScheduledExecutorService executor) {
        return new JobEventController(
                events,
                new WorkspaceAuthorizationService(new FixedWorkspaceRepository(hasWorkspaceAccess)),
                new FixedIdentityRepository(new UserIdentity(
                        7L, "https://issuer.example", "subject-7", null, null, null, null, null)),
                executor);
    }

    private static JobEvent event(long id) {
        return new JobEvent(
                id,
                42L,
                81L,
                new JobTarget("document", 19L, 3L),
                new JobStage("extract"),
                id,
                JobEventType.PROGRESS,
                JobState.LEASED,
                "Work is running.",
                1,
                3,
                OffsetDateTime.parse("2026-09-11T17:00:00Z"));
    }

    private static OidcUser oidcUserWith(String issuer, String subject) {
        OidcIdToken idToken = new OidcIdToken(
                "test-token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of(IdTokenClaimNames.ISS, issuer, IdTokenClaimNames.SUB, subject));
        return new DefaultOidcUser(List.of(), idToken);
    }

    private static final class CapturingJobEventRepository implements JobEventRepository {

        private final boolean cursorExists;
        private final List<JobEvent> results;
        private List<Long> existsArguments = List.of();
        private List<Long> findAfterArguments = List.of();

        private CapturingJobEventRepository(boolean cursorExists, List<JobEvent> results) {
            this.cursorExists = cursorExists;
            this.results = results;
        }

        @Override
        public List<JobEvent> findAfter(long workspaceId, long actorUserId, long afterEventId, int limit) {
            findAfterArguments = List.of(workspaceId, actorUserId, afterEventId, (long) limit);
            return results;
        }

        @Override
        public boolean exists(long workspaceId, long actorUserId, long eventId) {
            existsArguments = List.of(workspaceId, actorUserId, eventId);
            return cursorExists;
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

    private record FixedWorkspaceRepository(boolean hasWorkspaceAccess) implements WorkspaceRepository {

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
            return hasWorkspaceAccess ? Optional.of(WorkspaceRole.OWNER) : Optional.empty();
        }
    }
}
