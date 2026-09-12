package io.github.vihuynh72.brownie.api.identity.oidc;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
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
 * validation, userinfo call), this records who just logged in and
 * provisions their personal workspace on first login. The returned {@link
 * OidcUser} is the delegate's own, unmodified -- Brownie's internal
 * identity row is looked up separately, by issuer and subject, wherever it
 * is needed (see {@code MeController}), rather than carried on a custom
 * principal type.
 */
@Service
public class BrownieOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;

    @Autowired
    public BrownieOidcUserService(UserIdentityRepository userIdentityRepository, WorkspaceRepository workspaceRepository) {
        this(new OidcUserService(), userIdentityRepository, workspaceRepository);
    }

    /** Package-visible so a test can substitute a delegate that makes no real network call. */
    BrownieOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository) {
        this.delegate = delegate;
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        OidcIdToken idToken = oidcUser.getIdToken();
        UserIdentity identity = userIdentityRepository.recordLogin(
                idToken.getIssuer().toString(), idToken.getSubject(), oidcUser.getEmail(), oidcUser.getFullName());
        workspaceRepository.ensurePersonalWorkspace(identity.id());
        return oidcUser;
    }
}
