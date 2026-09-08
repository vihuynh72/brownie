package io.github.vihuynh72.brownie.api.identity.oidc;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolates issuer-subject identity mapping from the real network calls a
 * live {@link org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService}
 * delegate would make, which need a real identity provider the owner has
 * not set up yet.
 */
class BrownieOidcUserServiceTest {

    @Test
    void recordsTheLoginByIssuerAndSubjectFromTheIdTokenAndReturnsTheDelegateResultUnchanged() {
        OidcUser fakeOidcUser = oidcUserWith("https://issuer-x", "subject-x", "person@example.com", "Person Name");
        RecordingUserIdentityRepository identityRepository = new RecordingUserIdentityRepository();
        BrownieOidcUserService service =
                new BrownieOidcUserService((request) -> fakeOidcUser, identityRepository, new RecordingWorkspaceRepository());

        OidcUser result = service.loadUser(null);

        assertThat(result).isSameAs(fakeOidcUser);
        assertThat(identityRepository.issuer).isEqualTo("https://issuer-x");
        assertThat(identityRepository.subject).isEqualTo("subject-x");
        assertThat(identityRepository.email).isEqualTo("person@example.com");
        assertThat(identityRepository.displayName).isEqualTo("Person Name");
    }

    @Test
    void provisionsThePersonalWorkspaceForTheLoggedInIdentity() {
        OidcUser fakeOidcUser = oidcUserWith("https://issuer-y", "subject-y", "other@example.com", "Other Name");
        RecordingWorkspaceRepository workspaceRepository = new RecordingWorkspaceRepository();
        BrownieOidcUserService service = new BrownieOidcUserService(
                (request) -> fakeOidcUser, new RecordingUserIdentityRepository(), workspaceRepository);

        service.loadUser(null);

        assertThat(workspaceRepository.ensuredForUserId).isEqualTo(1L);
    }

    private static OidcUser oidcUserWith(String issuer, String subject, String email, String name) {
        OidcIdToken idToken = new OidcIdToken(
                "fake-token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of(
                        IdTokenClaimNames.ISS, issuer,
                        IdTokenClaimNames.SUB, subject,
                        StandardClaimNames.EMAIL, email,
                        StandardClaimNames.NAME, name));
        return new DefaultOidcUser(List.of(), idToken);
    }

    private static final class RecordingUserIdentityRepository implements UserIdentityRepository {
        private String issuer;
        private String subject;
        private String email;
        private String displayName;

        @Override
        public UserIdentity recordLogin(String issuer, String subject, String email, String displayName) {
            this.issuer = issuer;
            this.subject = subject;
            this.email = email;
            this.displayName = displayName;
            return new UserIdentity(1L, issuer, subject, email, displayName, null, null, null);
        }

        @Override
        public Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }

    private static final class RecordingWorkspaceRepository implements WorkspaceRepository {
        private Long ensuredForUserId;

        @Override
        public Workspace ensurePersonalWorkspace(long ownerUserId) {
            this.ensuredForUserId = ownerUserId;
            return new Workspace(1L, ownerUserId, "en-US", "UTC", WorkspaceStatus.ACTIVE, 1, "standard", true, null);
        }

        @Override
        public List<WorkspaceMember> findMembershipsForUser(long userId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Optional<WorkspaceRole> findRole(long workspaceId, long userId) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }
}
