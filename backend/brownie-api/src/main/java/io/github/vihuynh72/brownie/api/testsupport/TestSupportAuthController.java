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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * <p>Three independent guards keep this a development tool and nothing
 * more. {@code @Profile({"local", "test"})} keeps it unreachable on {@code
 * pilot}/{@code production}: {@code BrownieEnvironmentListener} resolves
 * {@code BROWNIE_ENVIRONMENT} into exactly one active Spring profile at
 * startup, so this bean is simply never created outside those two. The
 * expression condition means the route does not exist at all unless the
 * operator deliberately set a non-blank {@code brownie.test-support.token}
 * -- the local profile binds that property from an environment variable
 * with an empty default, and an empty value must read as "not
 * configured", not as a token; the ordinary local run of the app has no
 * such route. And every call must
 * arrive from the loopback interface carrying that exact token in {@code
 * X-Test-Support-Token}, so neither another device on the same network
 * nor a cross-site GET from a page the developer happens to visit can mint
 * a session that would then spend the developer's own real model key.
 *
 * <p>A GET, not a POST, deliberately: this is meant to be the very first
 * request a fresh browser context ever makes, before any CSRF cookie
 * exists to satisfy this app's own CSRF-on-every-mutation policy -- a real
 * login already begins the same way, from a plain anchor tag's GET to
 * {@code /oauth2/authorization/entra}. The token header is what a browser
 * cannot be tricked into sending.
 */
@RestController
@Profile({"local", "test"})
@ConditionalOnExpression("!'${brownie.test-support.token:}'.isBlank()")
class TestSupportAuthController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportAuthController.class);
    private static final String ISSUER = "https://e2e-test-support-issuer";
    private static final String TOKEN_HEADER = "X-Test-Support-Token";
    private static final SecurityContextHolderAwareSaver SESSION_SAVER = new SecurityContextHolderAwareSaver();

    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;
    private final BuiltInTemplateProvisioningService builtInTemplateProvisioningService;
    private final byte[] expectedToken;

    TestSupportAuthController(
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            BuiltInTemplateProvisioningService builtInTemplateProvisioningService,
            @Value("${brownie.test-support.token}") String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalStateException("brownie.test-support.token must not be blank when it is set at all.");
        }
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
        this.builtInTemplateProvisioningService = builtInTemplateProvisioningService;
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/test-support/sessions")
    SessionResponse createSession(
            @RequestParam String subject,
            @RequestHeader(value = TOKEN_HEADER, required = false) String presentedToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireLoopback(request);
        requireToken(presentedToken);

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

    /** Refuses anything that did not arrive over the loopback interface; a forwarded header is deliberately not consulted, since no trusted proxy exists in front of a local run. */
    private static void requireLoopback(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        try {
            if (remote == null || !InetAddress.getByName(remote).isLoopbackAddress()) {
                throw new AccessDeniedException("The test-support route only answers loopback clients.");
            }
        } catch (UnknownHostException e) {
            throw new AccessDeniedException("The test-support route only answers loopback clients.");
        }
    }

    /** Constant-time comparison, so a wrong token cannot be narrowed down byte by byte through response timing. */
    private void requireToken(String presentedToken) {
        byte[] presented = presentedToken == null ? new byte[0] : presentedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, presented)) {
            throw new AccessDeniedException("The test-support route requires the configured " + TOKEN_HEADER + ".");
        }
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
