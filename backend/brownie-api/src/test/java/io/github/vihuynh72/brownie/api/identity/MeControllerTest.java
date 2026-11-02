package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMemberState;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@code GET /api/v1/me} reports the caller's own workspace
 * memberships, without needing a live IdP-authenticated HTTP
 * request -- {@link org.springframework.security.oauth2.core.oidc.user.OidcUser}
 * is a plain value Spring Security supplies via {@code
 * @AuthenticationPrincipal}, so it can be built by hand here.
 */
class MeControllerTest {

    @Test
    void reportsIdentityAndWorkspaceMemberships() {
        UserIdentity identity =
                new UserIdentity(7L, "https://issuer-z", "subject-z", "z@example.com", "Z Name", null, null, null, null, null, null);
        StubUserIdentityRepository identityRepository = new StubUserIdentityRepository(identity);
        StubWorkspaceRepository workspaceRepository = new StubWorkspaceRepository(
                List.of(new WorkspaceMember(42L, 7L, WorkspaceRole.OWNER, WorkspaceMemberState.ACTIVE, null)));
        MeController controller = new MeController(identityRepository, workspaceRepository, new StubSavedDetailRepository());

        MeController.MeResponse response = controller.me(oidcUserWith("https://issuer-z", "subject-z"));

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.allowance()).isEqualTo("FULL");
        assertThat(response.invitationRequested()).isFalse();
        assertThat(response.memberships()).containsExactly(new MeController.MembershipResponse(42L, "OWNER"));
    }

    @Test
    void reportsTheOpenAllowanceAsFreeDaily() {
        UserIdentity identity =
                new UserIdentity(8L, "https://issuer-o", "subject-o", "o@example.com", "O Name", null, null, null, null, null, null);
        MeController controller = new MeController(
                new StubUserIdentityRepository(identity, true),
                new StubWorkspaceRepository(List.of(), true), new StubSavedDetailRepository());

        MeController.MeResponse response = controller.me(oidcUserWith("https://issuer-o", "subject-o"));

        assertThat(response.allowance()).isEqualTo("FREE_DAILY");
        assertThat(response.invitationRequested()).isTrue();
    }

    @Test
    void aLongInvitationRequestNoteIsRefused() {
        UserIdentity identity =
                new UserIdentity(9L, "https://issuer-n", "subject-n", "n@example.com", "N Name", null, null, null, null, null, null);
        MeController controller = new MeController(
                new StubUserIdentityRepository(identity), new StubWorkspaceRepository(List.of()),
                new StubSavedDetailRepository());

        assertThatThrownBy(() -> controller.invitationRequest(
                        oidcUserWith("https://issuer-n", "subject-n"),
                        new MeController.InvitationRequestBody("x".repeat(501))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("500");
    }

    @Test
    void savingANameConfirmsItAndWritesTheThreePersonDetails() {
        UserIdentity identity =
                new UserIdentity(7L, "https://issuer-z", "subject-z", "z@example.com", "Z Name", null, null, null, null, null, null);
        StubSavedDetailRepository details = new StubSavedDetailRepository();
        MeController controller = new MeController(
                new StubUserIdentityRepository(identity), new StubWorkspaceRepository(List.of()), details);

        MeController.MeResponse response = controller.saveName(
                oidcUserWith("https://issuer-z", "subject-z"),
                new MeController.SaveNameRequest("  Daniel  ", " Ortiz "));

        assertThat(response.givenName()).isEqualTo("Daniel");
        assertThat(response.familyName()).isEqualTo("Ortiz");
        assertThat(response.nameConfirmed()).isTrue();
        assertThat(details.writes).containsExactly(
                new String[]{"person.first_name", "Daniel", "MANUAL"},
                new String[]{"person.last_name", "Ortiz", "MANUAL"},
                new String[]{"person.full_name", "Daniel Ortiz", "MANUAL"});
    }

    @Test
    void aBlankOrOverLongNamePartIsRefused() {
        UserIdentity identity =
                new UserIdentity(7L, "https://issuer-z", "subject-z", "z@example.com", "Z Name", null, null, null, null, null, null);
        MeController controller = new MeController(
                new StubUserIdentityRepository(identity), new StubWorkspaceRepository(List.of()),
                new StubSavedDetailRepository());

        assertThatThrownBy(() -> controller.saveName(
                        oidcUserWith("https://issuer-z", "subject-z"),
                        new MeController.SaveNameRequest("   ", "Ortiz")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("givenName");
        assertThatThrownBy(() -> controller.saveName(
                        oidcUserWith("https://issuer-z", "subject-z"),
                        new MeController.SaveNameRequest("Daniel", "x".repeat(101))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("familyName");
    }

    private static OidcUser oidcUserWith(String issuer, String subject) {
        OidcIdToken idToken = new OidcIdToken(
                "fake-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of(IdTokenClaimNames.ISS, issuer, IdTokenClaimNames.SUB, subject));
        return new DefaultOidcUser(List.of(), idToken);
    }

    private record StubUserIdentityRepository(UserIdentity identity, boolean invitationRequested,
                                              java.util.concurrent.atomic.AtomicReference<UserIdentity> named)
            implements UserIdentityRepository {
        StubUserIdentityRepository(UserIdentity identity) {
            this(identity, false, new java.util.concurrent.atomic.AtomicReference<>());
        }

        StubUserIdentityRepository(UserIdentity identity, boolean invitationRequested) {
            this(identity, invitationRequested, new java.util.concurrent.atomic.AtomicReference<>());
        }

        @Override
        public UserIdentity recordLogin(String issuer, String subject, String email, String displayName) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public UserIdentity recordName(long userId, String givenName, String familyName) {
            named.set(new UserIdentity(identity.id(), identity.issuer(), identity.subject(), identity.email(),
                    identity.displayName(), identity.createdAt(), identity.lastLoginAt(), identity.disabledAt(),
                    givenName, familyName, java.time.OffsetDateTime.now()));
            return named.get();
        }

        @Override
        public Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject) {
            return Optional.of(named.get() != null ? named.get() : identity);
        }

        @Override
        public void recordInvitationRequest(long userId, String note) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public boolean hasInvitationRequest(long userId) {
            return invitationRequested;
        }
    }

    private record StubWorkspaceRepository(List<WorkspaceMember> memberships, boolean openAllowance) implements WorkspaceRepository {
        StubWorkspaceRepository(List<WorkspaceMember> memberships) {
            this(memberships, false);
        }


        @Override
        public Workspace ensurePersonalWorkspace(long ownerUserId) {
            return new Workspace(42L, ownerUserId, null, null, io.github.vihuynh72.brownie.core.workspace.WorkspaceStatus.ACTIVE,
                    1, null, true, java.time.OffsetDateTime.now());
        }

        @Override
        public List<WorkspaceMember> findMembershipsForUser(long userId) {
            return memberships;
        }

        @Override
        public Optional<WorkspaceRole> findRole(long workspaceId, long userId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public boolean setOpenAllowance(long workspaceId, long userId, boolean open) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<Boolean> findOpenAllowance(long userId) {
            return Optional.of(openAllowance);
        }
    }

    /** Records each upsert so a test can see which details a save wrote. */
    private static final class StubSavedDetailRepository
            implements io.github.vihuynh72.brownie.core.detail.SavedDetailRepository {
        final java.util.List<String[]> writes = new java.util.ArrayList<>();

        @Override
        public java.util.List<io.github.vihuynh72.brownie.core.detail.SavedDetail> list(long ownerUserId) {
            return java.util.List.of();
        }

        @Override
        public io.github.vihuynh72.brownie.core.detail.SavedDetail upsert(
                long ownerUserId, long workspaceId, String key, String label, String valueText, byte[] valueSealed,
                String valueHint, boolean sensitive, io.github.vihuynh72.brownie.core.detail.DetailOrigin origin,
                Long sourceDocumentId, String sourceLabel, String sourceTitle) {
            writes.add(new String[]{key, valueText, origin.name()});
            return null;
        }

        @Override
        public void delete(long ownerUserId, String key) {
        }

        @Override
        public void deleteAll(long ownerUserId) {
        }
    }
}
