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
                new UserIdentity(7L, "https://issuer-z", "subject-z", "z@example.com", "Z Name", null, null, null);
        StubUserIdentityRepository identityRepository = new StubUserIdentityRepository(identity);
        StubWorkspaceRepository workspaceRepository = new StubWorkspaceRepository(
                List.of(new WorkspaceMember(42L, 7L, WorkspaceRole.OWNER, WorkspaceMemberState.ACTIVE, null)));
        MeController controller = new MeController(identityRepository, workspaceRepository);

        MeController.MeResponse response = controller.me(oidcUserWith("https://issuer-z", "subject-z"));

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.memberships()).containsExactly(new MeController.MembershipResponse(42L, "OWNER"));
    }

    private static OidcUser oidcUserWith(String issuer, String subject) {
        OidcIdToken idToken = new OidcIdToken(
                "fake-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of(IdTokenClaimNames.ISS, issuer, IdTokenClaimNames.SUB, subject));
        return new DefaultOidcUser(List.of(), idToken);
    }

    private record StubUserIdentityRepository(UserIdentity identity) implements UserIdentityRepository {
        @Override
        public UserIdentity recordLogin(String issuer, String subject, String email, String displayName) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject) {
            return Optional.of(identity);
        }
    }

    private record StubWorkspaceRepository(List<WorkspaceMember> memberships) implements WorkspaceRepository {
        @Override
        public Workspace ensurePersonalWorkspace(long ownerUserId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public List<WorkspaceMember> findMembershipsForUser(long userId) {
            return memberships;
        }
    }
}
