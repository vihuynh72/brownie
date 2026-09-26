package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.api.connector.google.GoogleConsentRequests;
import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.ResourceGrant;
import io.github.vihuynh72.brownie.core.connector.ResourceGrantRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A person's connections to their Google account in this workspace: what
 * exists, starting a consent, and disconnecting.
 *
 * <p>Starting a consent is a POST, checked against the session's CSRF token
 * like every other change, that answers with the address of Google's consent
 * page rather than redirecting to it: a fetch cannot follow a redirect to
 * another site, and the page would otherwise have to be a form, which the
 * page's content policy does not let redirect anywhere else. The page then
 * navigates there itself. Nothing is stored in the database until Google
 * sends the person back with a code and the callback controller exchanges it.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/connections")
class ConnectionController {

    private final ConnectorService connectorService;
    private final GoogleConnectorSetup googleConnectorSetup;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;
    private final ResourceGrantRepository resourceGrantRepository;

    ConnectionController(
            ConnectorService connectorService,
            GoogleConnectorSetup googleConnectorSetup,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository,
            ResourceGrantRepository resourceGrantRepository) {
        this.connectorService = connectorService;
        this.googleConnectorSetup = googleConnectorSetup;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
        this.resourceGrantRepository = resourceGrantRepository;
    }

    /** Every connection this person has made here, disconnected ones included, newest first. */
    @GetMapping
    List<ConnectionResponse> list(@PathVariable("workspaceId") long workspaceId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        return responses(resourceGrantRepository, workspaceId, userId, connectorService.connections(workspaceId, userId));
    }

    @PostMapping("/google")
    ResponseEntity<ConsentStartResponse> startGoogleConsent(
            @PathVariable("workspaceId") long workspaceId,
            @RequestBody StartConsentRequest request,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletRequest httpRequest) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        ConnectorAccess access = parseAccess(request.access());
        String returnTo = request.returnTo() == null ? "/connections" : request.returnTo();
        if (!PendingConsent.isAllowedReturnTo(returnTo)) {
            throw new ConnectionRequestValidationException("returnTo must be /connections or a document's own address.");
        }
        if (!googleConnectorSetup.configured()) {
            throw new ConnectorNotConfiguredException();
        }
        String state = GoogleConsentRequests.newState();
        String codeVerifier = GoogleConsentRequests.newCodeVerifier();
        new PendingConsent(state, codeVerifier, workspaceId, userId, access, returnTo, Instant.now().getEpochSecond(), false)
                .storeIn(httpRequest.getSession());
        String authorizationUrl = GoogleConsentRequests
                .authorizationUri(googleConnectorSetup.settings(), access, state, GoogleConsentRequests.challengeFor(codeVerifier))
                .toString();
        // It carries this session's state: not something a shared cache may keep.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ConsentStartResponse(authorizationUrl));
    }

    /**
     * Disconnects every Google connection this person has here: each is
     * revoked at Google and its token wiped whatever Google answers. Returns
     * every connection as it now stands. Asking again changes nothing.
     */
    @PostMapping("/google/disconnect")
    List<ConnectionResponse> disconnectGoogle(@PathVariable("workspaceId") long workspaceId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        connectorService.disconnectAll(workspaceId, userId);
        return responses(resourceGrantRepository, workspaceId, userId, connectorService.connections(workspaceId, userId));
    }

    /** A disconnected connection has had every choice revoked, so only an open one is asked for what it may read. */
    static List<ConnectionResponse> responses(
            ResourceGrantRepository resourceGrantRepository, long workspaceId, long userId, List<Connection> connections) {
        return connections.stream()
                .map(connection -> ConnectionResponse.from(connection, connection.isOpen()
                        ? resourceGrantRepository.findOpen(workspaceId, userId, connection.id())
                        : List.of()))
                .toList();
    }

    private static ConnectorAccess parseAccess(String access) {
        if (access != null) {
            for (ConnectorAccess candidate : ConnectorAccess.values()) {
                if (candidate.name().equals(access)) {
                    return candidate;
                }
            }
        }
        throw new ConnectionRequestValidationException("access must be DRIVE_FILES or CALENDAR_EVENTS.");
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    record StartConsentRequest(String access, String returnTo) {
    }

    record ConsentStartResponse(String authorizationUrl) {
    }

    /**
     * What a person may see of a connection: never its token, and not the
     * provider's internal account identifier, which means nothing to them.
     */
    record ConnectionResponse(
            long id,
            String provider,
            String access,
            String state,
            String accountEmail,
            List<String> grantedScopes,
            String reconnectReason,
            OffsetDateTime connectedAt,
            OffsetDateTime tokenIssuedAt,
            OffsetDateTime disconnectedAt,
            String providerRevocation,
            List<ResourceGrantResponse> grants) {

        static ConnectionResponse from(Connection connection, List<ResourceGrant> openGrants) {
            return new ConnectionResponse(
                    connection.id(),
                    "GOOGLE",
                    connection.access().name(),
                    connection.state().name(),
                    connection.accountEmail(),
                    connection.grantedScopes(),
                    connection.reconnectReason() == null ? null : connection.reconnectReason().name(),
                    connection.connectedAt(),
                    connection.tokenIssuedAt(),
                    connection.disconnectedAt(),
                    connection.providerRevocation() == null ? null : connection.providerRevocation().name(),
                    openGrants.stream().map(ResourceGrantResponse::from).toList());
        }
    }

    /**
     * Something the person chose for Brownie to read through a connection, as
     * a page shows it: what kind of thing, what it is called, and since when.
     * Never the provider's own identifier for it.
     */
    record ResourceGrantResponse(long id, String type, String displayName, OffsetDateTime grantedAt) {

        static ResourceGrantResponse from(ResourceGrant grant) {
            return new ResourceGrantResponse(grant.id(), grant.type().name(), grant.displayName(), grant.grantedAt());
        }
    }
}
