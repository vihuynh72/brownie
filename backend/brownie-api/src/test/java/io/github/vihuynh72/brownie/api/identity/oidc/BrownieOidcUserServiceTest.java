package io.github.vihuynh72.brownie.api.identity.oidc;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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
 * Isolates the one behavior the master plan names explicitly for P04-01 --
 * issuer-subject identity mapping -- from the real network calls a live
 * {@link org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService}
 * delegate would make, which need a real identity provider the owner has
 * not set up yet (§18.6).
 */
class BrownieOidcUserServiceTest {

    @Test
    void recordsTheLoginByIssuerAndSubjectFromTheIdTokenAndReturnsTheDelegateResultUnchanged() {
        OidcUser fakeOidcUser = oidcUserWith("https://issuer-x", "subject-x", "person@example.com", "Person Name");
        RecordingUserIdentityRepository repository = new RecordingUserIdentityRepository();
        BrownieOidcUserService service = new BrownieOidcUserService((request) -> fakeOidcUser, repository);

        OidcUser result = service.loadUser(null);

        assertThat(result).isSameAs(fakeOidcUser);
        assertThat(repository.issuer).isEqualTo("https://issuer-x");
        assertThat(repository.subject).isEqualTo("subject-x");
        assertThat(repository.email).isEqualTo("person@example.com");
        assertThat(repository.displayName).isEqualTo("Person Name");
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
}
