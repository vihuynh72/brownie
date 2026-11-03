package io.github.vihuynh72.brownie.api.identity.oidc;

import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.api.identity.oidc.InvitedPeople.Access;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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
    /** What every test but the invitation ones wants: no gate. */
    private static final InvitedPeople EVERYONE = new InvitedPeople(false, "");

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BuiltInTemplateProvisioningService builtInTemplateProvisioningService;
    private final InvitedPeople invitedPeople;

    @Autowired
    public BrownieOidcUserService(
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            BuiltInTemplateProvisioningService builtInTemplateProvisioningService,
            InvitedPeople invitedPeople) {
        this(
                new OidcUserService(),
                userIdentityRepository,
                workspaceRepository,
                builtInTemplateProvisioningService,
                invitedPeople);
    }

    /** Package-visible so a test can substitute a delegate that makes no real network call. */
    BrownieOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            BuiltInTemplateProvisioningService builtInTemplateProvisioningService) {
        this(delegate, userIdentityRepository, workspaceRepository, builtInTemplateProvisioningService, EVERYONE);
    }

    BrownieOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate,
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            BuiltInTemplateProvisioningService builtInTemplateProvisioningService,
            InvitedPeople invitedPeople) {
        this.delegate = delegate;
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
        this.builtInTemplateProvisioningService = builtInTemplateProvisioningService;
        this.invitedPeople = invitedPeople;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        String registrationId = userRequest != null && userRequest.getClientRegistration() != null
                ? userRequest.getClientRegistration().getRegistrationId()
                : null;
        Access access = accessOf(oidcUser, registrationId);
        OidcIdToken idToken = oidcUser.getIdToken();
        UserIdentity identity = userIdentityRepository.recordLogin(
                idToken.getIssuer().toString(), idToken.getSubject(), oidcUser.getEmail(), displayNameOf(oidcUser),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()));
        if ("google".equals(registrationId)) {
            prefillProviderNames(identity.id(), oidcUser);
        }
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(identity.id());
        setOpenAllowanceOrRefuse(workspace.id(), identity.id(), access);
        ensureBuiltInTemplatesWithoutBreakingLogin(workspace.id(), identity.id());
        return oidcUser;
    }

    /**
     * Checked on every sign-in, not only the first, so that withdrawing an
     * invitation ends access rather than only preventing a new account.
     *
     * <p>Nothing about the person is written to the log. The provider keeps
     * its own record of who tried to sign in, and an address in an
     * application log is one more copy of somebody's personal information in
     * a place nobody thought about.
     */
    private Access accessOf(OidcUser oidcUser, String registrationId) {
        // Google's own verification is the only claim worth trusting for a
        // match against the invitation list: an address the provider never
        // confirmed belongs to the person is no address at all, so the
        // sign-in is judged exactly like one that came with none.
        String email = "google".equals(registrationId) && !Boolean.TRUE.equals(oidcUser.getEmailVerified())
                ? null
                : oidcUser.getEmail();
        Access access = invitedPeople.accessFor(email);
        if (access != Access.REFUSED) {
            return access;
        }
        log.warn("A sign-in was refused because the account is not on the invitation list.");
        throw new OAuth2AuthenticationException(
                new OAuth2Error(
                        "not_invited",
                        "This Brownie is open to invited people only.",
                        null),
                "This Brownie is open to invited people only.");
    }

    /**
     * Written on every sign-in, not only the first, so that inviting
     * someone who signed up before they were invited moves them off the
     * open allowance at their next sign-in, and dropping someone moves
     * them back on. An open person's sign-in must not go through without
     * the flag that prices their calls: failing closed keeps the shared
     * pool honest; an invited person's allowance stays the ordinary one,
     * so a failed write only needs a warning.
     */
    private void setOpenAllowanceOrRefuse(long workspaceId, long userId, Access access) {
        try {
            if (workspaceRepository.setOpenAllowance(workspaceId, userId, access == Access.OPEN)) {
                return;
            }
            if (access == Access.OPEN) {
                refuseAllowanceSetup();
            }
            log.warn("A sign-in could not clear its workspace's open-allowance flag.");
        } catch (RuntimeException e) {
            if (access == Access.OPEN) {
                refuseAllowanceSetup();
            }
            log.warn("A sign-in could not clear its workspace's open-allowance flag.");
        }
    }

    private static void refuseAllowanceSetup() {
        throw new OAuth2AuthenticationException(
                new OAuth2Error(
                        "allowance_not_set",
                        "Brownie could not set up your free allowance. Try signing in again.",
                        null),
                "Brownie could not set up your free allowance. Try signing in again.");
    }

    /**
     * Google's given and family names only ever fill the card's blank
     * fields: the person may already have typed one of the two, and a
     * confirmed name is never rewritten by what a provider calls them.
     */
    private void prefillProviderNames(long userId, OidcUser oidcUser) {
        String given = oidcUser.getGivenName();
        String family = oidcUser.getFamilyName();
        if ((given == null || given.isBlank()) && (family == null || family.isBlank())) {
            return;
        }
        userIdentityRepository.prefillNames(userId, given, family);
    }

    /**
     * The token's {@code name} claim, or null when the provider has none:
     * Entra External ID sends the literal word "unknown" for an account
     * with no display name, which is not a name anyone should be greeted
     * by. A null here lets the client fall back to the email address.
     */
    static String displayNameOf(OidcUser oidcUser) {
        String name = oidcUser.getFullName();
        if (name == null || name.isBlank() || name.strip().equalsIgnoreCase("unknown")) {
            return null;
        }
        return name.strip();
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
