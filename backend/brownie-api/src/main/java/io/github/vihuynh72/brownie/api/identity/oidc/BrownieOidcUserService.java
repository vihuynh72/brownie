package io.github.vihuynh72.brownie.api.identity.oidc;

import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * validation, userinfo call), this records who just logged in, provisions
 * their personal workspace on first login, and makes sure that workspace
 * has its built-in templates to choose from. The returned {@link OidcUser}
 * is the delegate's own, unmodified -- Brownie's internal identity row is
 * looked up separately, by issuer and subject, wherever it is needed (see
 * {@code MeController}), rather than carried on a custom principal type.
 */
@Service
public class BrownieOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger log = LoggerFactory.getLogger(BrownieOidcUserService.class);

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    @Autowired
    public BrownieOidcUserService(
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            BuiltInTemplateProvisioningService builtInTemplateProvisioningService) {
        this(new OidcUserService(), userIdentityRepository, workspaceRepository, builtInTemplateProvisioningService);
    }

    /** Package-visible so a test can substitute a delegate that makes no real network call. */
    BrownieOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            BuiltInTemplateProvisioningService builtInTemplateProvisioningService) {
        this.delegate = delegate;
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
        this.builtInTemplateProvisioningService = builtInTemplateProvisioningService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        OidcIdToken idToken = oidcUser.getIdToken();
        UserIdentity identity = userIdentityRepository.recordLogin(
                idToken.getIssuer().toString(), idToken.getSubject(), oidcUser.getEmail(), oidcUser.getFullName());
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(identity.id());
        ensureBuiltInTemplatesWithoutBreakingLogin(workspace.id(), identity.id());
        return oidcUser;
    }

    /**
     * A person waiting on this login must not be locked out by an
     * unrelated infrastructure hiccup (for instance, the isolated renderer
     * template activation depends on being briefly unavailable) --
     * provisioning is retried the next time this same workspace logs in,
     * since {@link BuiltInTemplateProvisioningService#ensureBuiltInTemplates}
     * is written to be safely repeatable.
     */
    private void ensureBuiltInTemplatesWithoutBreakingLogin(long workspaceId, long userId) {
        try {
            builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        } catch (RuntimeException e) {
            log.warn("Failed to provision built-in templates for workspace {}; will retry on next login.", workspaceId, e);
        }
    }
}
