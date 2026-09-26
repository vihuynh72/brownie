package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.api.connector.ConnectionController.ConnectionResponse;
import io.github.vihuynh72.brownie.api.connector.ConnectionController.ConsentStartResponse;
import io.github.vihuynh72.brownie.api.connector.google.GoogleConsentRequests;
import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.source.DocumentSourceResponse;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.DriveImportService;
import io.github.vihuynh72.brownie.core.connector.ResourceGrantRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Google Drive files the person picks: choosing them with Google's own
 * picker, copying one into a document, and telling Brownie to stop reading
 * one. There is no route that lists or searches Drive. The files a person has
 * picked are their Drive connection's open grants, which the connections
 * list already shows by Brownie's own id and name, never by Drive's id; a
 * copy names a grant, never a file.
 *
 * <p>Starting a pick is like starting a consent (a CSRF-checked POST
 * answering with Google's address, which the page navigates to), except that
 * Google shows its file picker after the consent and sends the chosen files'
 * ids back with the code. A pick also connects Drive, the way connecting
 * again does, so it needs no Drive connection to exist first. Every answer
 * here is the person's own, so none may be kept by a shared cache, and every
 * route sits under the connection's own path, which puts it in the rate class
 * for work that calls Google.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/connections/google/drive")
class DriveController {

    private final GoogleConnectorSetup googleConnectorSetup;
    private final DriveOffer driveOffer;
    private final DriveImportService driveImportService;
    private final ConnectorService connectorService;
    private final ResourceGrantRepository resourceGrantRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    DriveController(
            GoogleConnectorSetup googleConnectorSetup,
            DriveOffer driveOffer,
            DriveImportService driveImportService,
            ConnectorService connectorService,
            ResourceGrantRepository resourceGrantRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.googleConnectorSetup = googleConnectorSetup;
        this.driveOffer = driveOffer;
        this.driveImportService = driveImportService;
        this.connectorService = connectorService;
        this.resourceGrantRepository = resourceGrantRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping("/picks")
    ResponseEntity<ConsentStartResponse> startPick(
            @PathVariable("workspaceId") long workspaceId,
            @RequestBody(required = false) StartPickRequest request,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletRequest httpRequest) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        String returnTo = request == null || request.returnTo() == null ? "/connections" : request.returnTo();
        if (!PendingConsent.isAllowedReturnTo(returnTo)) {
            throw new ConnectionRequestValidationException("returnTo must be /connections or a document's own address.");
        }
        requireOffered();
        String state = GoogleConsentRequests.newState();
        String codeVerifier = GoogleConsentRequests.newCodeVerifier();
        new PendingConsent(state, codeVerifier, workspaceId, userId, ConnectorAccess.DRIVE_FILES, returnTo,
                Instant.now().getEpochSecond(), true)
                .storeIn(httpRequest.getSession());
        String authorizationUrl = GoogleConsentRequests
                .drivePickUri(googleConnectorSetup.settings(), state, GoogleConsentRequests.challengeFor(codeVerifier), true)
                .toString();
        // It carries this session's state: not something a shared cache may keep.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ConsentStartResponse(authorizationUrl));
    }

    /** Copies a picked file into the document, or links a copy already made of it; {@code newCopy} says which. */
    @PostMapping("/imports")
    ResponseEntity<DriveImportResponse> importFile(
            @PathVariable("workspaceId") long workspaceId,
            @RequestBody ImportRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        if (request.documentId() == null || request.documentId() <= 0) {
            throw new ConnectionRequestValidationException("documentId must be positive.");
        }
        if (request.grantId() == null || request.grantId() <= 0) {
            throw new ConnectionRequestValidationException("grantId must be positive.");
        }
        requireOffered();
        DriveImportService.ImportOutcome outcome =
                driveImportService.importFile(workspaceId, userId, request.documentId(), request.grantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new DriveImportResponse(DocumentSourceResponse.from(outcome.source()), outcome.newCopy()));
    }

    /**
     * Stops Brownie reading one picked file; copies already made from it stay
     * where they are used. Answers with every connection as it now stands, as
     * disconnecting does. Never refused for Drive being switched off: a person
     * can always take a choice back.
     */
    @PostMapping("/files/{grantId}/forget")
    ResponseEntity<List<ConnectionResponse>> forget(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("grantId") long grantId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_CONNECTIONS);
        driveImportService.forget(workspaceId, userId, grantId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ConnectionController.responses(resourceGrantRepository, workspaceId, userId, connectorService.connections(workspaceId, userId)));
    }

    private void requireOffered() {
        if (!driveOffer.offered()) {
            throw new ConnectorNotConfiguredException("Choosing and copying Google Drive files is not offered on this Brownie at the moment.");
        }
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    record StartPickRequest(String returnTo) {
    }

    record ImportRequest(Long documentId, Long grantId) {
    }

    record DriveImportResponse(DocumentSourceResponse source, boolean newCopy) {
    }
}
