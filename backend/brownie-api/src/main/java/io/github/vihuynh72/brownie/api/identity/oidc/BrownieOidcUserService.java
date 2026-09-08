package io.github.vihuynh72.brownie.api.identity.oidc;

import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Where issuer-subject identity mapping actually happens: after the
 * delegate has done the real OIDC work (token exchange, ID token
 * validation, userinfo call), this records who just logged in. The
 * returned {@link OidcUser} is the delegate's own, unmodified -- Brownie's
 * internal identity row is looked up separately, by issuer and subject,
 * wherever it is needed (see {@code MeController}), rather than carried on
 * a custom principal type.
 */
@Service
public class BrownieOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserIdentityRepository userIdentityRepository;

    @Autowired
    public BrownieOidcUserService(UserIdentityRepository userIdentityRepository) {
        this(new OidcUserService(), userIdentityRepository);
    }

    /** Package-visible so a test can substitute a delegate that makes no real network call. */
    BrownieOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate, UserIdentityRepository userIdentityRepository) {
        this.delegate = delegate;
        this.userIdentityRepository = userIdentityRepository;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        OidcIdToken idToken = oidcUser.getIdToken();
        userIdentityRepository.recordLogin(
                idToken.getIssuer().toString(), idToken.getSubject(), oidcUser.getEmail(), oidcUser.getFullName());
        return oidcUser;
    }
}
