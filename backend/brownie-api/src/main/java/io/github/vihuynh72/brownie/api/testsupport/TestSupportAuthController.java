package io.github.vihuynh72.brownie.api.testsupport;

import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Establishes a real, authenticated session for a fake issuer/subject
 * without a real identity provider round trip -- the same shortcut this
 * codebase's own MockMvc integration tests already take (see e.g. {@code
 * ExportValidationAdversarialIntegrationTest#loginAndGetSessionCookie}),
 * exposed as a real HTTP route so a real browser (Playwright) can drive
 * the actual running app past its own OIDC-redirect-only login without a
 * live external tenant.
 *
 * <p>{@code @Profile({"local", "test"})} keeps this unreachable on {@code
 * pilot}/{@code production} -- {@code BrownieEnvironmentListener} resolves
 * {@code BROWNIE_ENVIRONMENT} into exactly one active Spring profile at
 * startup, so this bean is simply never created outside those two, the
 * same boundary {@code BlobStorageConfig} already draws around Azurite's
 * local-only storage connection. A GET, not a POST, deliberately: this is
 * meant to be the very first request a fresh browser context ever makes,
 * before any CSRF cookie exists to satisfy this app's own CSRF-on-every-
 * mutation policy -- a real login already begins the same way, from a
 * plain anchor tag's GET to {@code /oauth2/authorization/entra}.
 */
@RestController
@Profile({"local", "test"})
class TestSupportAuthController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportAuthController.class);
    private static final String ISSUER = "https://e2e-test-support-issuer";
    private static final SecurityContextHolderAwareSaver SESSION_SAVER = new SecurityContextHolderAwareSaver();

    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    TestSupportAuthController(
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            BuiltInTemplateProvisioningService builtInTemplateProvisioningService) {
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
        this.builtInTemplateProvisioningService = builtInTemplateProvisioningService;
    }

    @GetMapping("/test-support/sessions")
    SessionResponse createSession(
            @RequestParam String subject, HttpServletRequest request, HttpServletResponse response) {
        UserIdentity identity = userIdentityRepository.recordLogin(ISSUER, subject, null, subject);
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(identity.id());
        ensureBuiltInTemplatesWithoutBreakingLogin(workspace.id(), identity.id());

        OidcIdToken idToken = OidcIdToken.withTokenValue("e2e-test-support-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        OAuth2AuthenticationToken authentication =
                new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra");

        SESSION_SAVER.save(authentication, request, response);

        return new SessionResponse(identity.id(), workspace.id());
    }

    private void ensureBuiltInTemplatesWithoutBreakingLogin(long workspaceId, long userId) {
        try {
            builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        } catch (RuntimeException e) {
            log.warn("Failed to provision built-in templates for e2e workspace {}; continuing without them.", workspaceId, e);
        }
    }

    record SessionResponse(long userId, long workspaceId) {
    }

    /** Isolates the small, imperative save-the-context dance so the controller method above reads as plain request handling. */
    private static final class SecurityContextHolderAwareSaver {
        private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

        void save(OAuth2AuthenticationToken authentication, HttpServletRequest request, HttpServletResponse response) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            repository.saveContext(context, request, response);
        }
    }
}
