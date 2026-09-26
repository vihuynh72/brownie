package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.connector.ConnectionAccountMismatchException;
import io.github.vihuynh72.brownie.core.connector.ConnectionNotFoundException;
import io.github.vihuynh72.brownie.core.connector.ConnectorBlockedByOrganizationException;
import io.github.vihuynh72.brownie.core.connector.ConnectorConsentIncompleteException;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;
import io.github.vihuynh72.brownie.core.connector.ConnectorPermissionNotGrantedException;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.DriveImportService;
import io.github.vihuynh72.brownie.core.connector.ProviderMisconfiguredException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import io.github.vihuynh72.brownie.core.connector.UsableConnection;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where Google sends a person back after they agreed, or declined, on
 * Google's consent page. The route needs the Brownie session like every
 * other one, and the pending consent in that session is what makes the
 * answer acceptable: it is taken out of the session first, so it is used
 * once at most; the state Google echoes back must equal it; it must be
 * recent; and it must belong to the person the session names, who must still
 * be allowed to connect accounts in that workspace. Only then is the code
 * exchanged.
 *
 * <p>Whatever happens, the person is sent back to the page they started
 * from, with {@code google=connected} or {@code google=failed} and a short
 * reason code the page turns into words. The code is one of a fixed set, or
 * Google's own error code when it is a plain lowercase word, so nothing
 * Google or anyone else put in the query string reaches the page as text.
 *
 * <p>A consent started to choose Drive files comes back the same way, with
 * the chosen files' ids beside the code. It is checked and completed exactly
 * like connecting again; only then are the ids, from this single-use and
 * state-checked answer and nowhere else, recorded as the files Brownie may
 * read, and the page is sent back with {@code google=picked} and how many
 * were added and why the rest were not. What is logged is counts, never an
 * id, the code or a token.
 */
@RestController
class GoogleConsentCallbackController {

    private static final Logger log = LoggerFactory.getLogger(GoogleConsentCallbackController.class);
    private static final Pattern SAFE_PROVIDER_ERROR = Pattern.compile("^[a-z_]{1,64}$");

    private final ConnectorService connectorService;
    private final DriveImportService driveImportService;
    private final GoogleConnectorSetup googleConnectorSetup;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;
    private final String webOrigin;

    GoogleConsentCallbackController(
            ConnectorService connectorService,
            DriveImportService driveImportService,
            GoogleConnectorSetup googleConnectorSetup,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository,
            @Value("${brownie.web.origin}") String webOrigin) {
        this.connectorService = connectorService;
        this.driveImportService = driveImportService;
        this.googleConnectorSetup = googleConnectorSetup;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
        this.webOrigin = webOrigin;
    }

    @GetMapping(ConnectorConfig.CALLBACK_PATH)
    ResponseEntity<Void> callback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "iss", required = false) String issuer,
            @RequestParam(name = "picked_file_ids", required = false) String pickedFileIds,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletRequest request) {
        PendingConsent pending = PendingConsent.takeFrom(request.getSession(false));
        if (pending == null) {
            return failed("/connections", null, "no_pending_request");
        }
        if (state == null || !MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), pending.state().getBytes(StandardCharsets.UTF_8))) {
            return failed(pending.returnTo(), pending, "state_mismatch");
        }
        if (pending.hasExpiredAt(Instant.now())) {
            return failed(pending.returnTo(), pending, "expired");
        }
        long userId = currentUserId(principal);
        if (userId != pending.userId()) {
            return failed(pending.returnTo(), pending, "state_mismatch");
        }
        try {
            workspaceAuthorizationService.requireCapability(userId, pending.workspaceId(), WorkspaceCapability.MANAGE_CONNECTIONS);
        } catch (AccessDeniedException e) {
            return failed(pending.returnTo(), pending, "not_allowed");
        }
        if (!googleConnectorSetup.configured()) {
            return failed(pending.returnTo(), pending, "not_configured");
        }
        // Google says who issued this answer; one that names someone else was not Google's.
        if (issuer != null && !issuer.equals(googleConnectorSetup.settings().issuer())) {
            return failed(pending.returnTo(), pending, "issuer_mismatch");
        }
        if (error != null) {
            String reason = SAFE_PROVIDER_ERROR.matcher(error).matches() ? error : "provider_error";
            return failed(pending.returnTo(), pending, reason);
        }
        if (code == null || code.isBlank()) {
            return failed(pending.returnTo(), pending, "no_code");
        }
        UsableConnection connected;
        try {
            connected = connectorService.completeConsent(pending.workspaceId(), userId, pending.access(), code, pending.codeVerifier());
        } catch (ConnectorPermissionNotGrantedException e) {
            return failed(pending.returnTo(), pending, "permission_not_granted");
        } catch (ConnectorConsentIncompleteException e) {
            return failed(pending.returnTo(), pending, "no_refresh_token");
        } catch (ConnectionAccountMismatchException e) {
            return failed(pending.returnTo(), pending, "different_account");
        } catch (ConnectorBlockedByOrganizationException e) {
            return failed(pending.returnTo(), pending, "blocked_by_organization");
        } catch (ProviderTokenRejectedException e) {
            return failed(pending.returnTo(), pending, "consent_expired");
        } catch (ProviderUnavailableException e) {
            return failed(pending.returnTo(), pending, "google_unavailable");
        } catch (ProviderMisconfiguredException | ConnectorNotConfiguredException e) {
            return failed(pending.returnTo(), pending, "not_configured");
        }
        if (!pending.pick()) {
            return redirect(pending.returnTo(), "google=connected&access=" + accessWord(pending));
        }
        return recordPicks(pending, userId, connected, pickedFileIds);
    }

    /**
     * After a pick's consent has completed: the ids Google sent beside it
     * become the files Brownie may read, as far as Drive confirms them. A
     * pick with nothing chosen still connected Drive, and says so; a pick
     * that stopped partway says what it added and why it stopped
     * ({@code stopped}), because Drive is connected by then.
     */
    private ResponseEntity<Void> recordPicks(PendingConsent pending, long userId, UsableConnection drive, String pickedFileIds) {
        List<String> ids = pickedFileIds == null || pickedFileIds.isBlank() ? List.of() : List.of(pickedFileIds.split(",", -1));
        DriveImportService.PickOutcome outcome;
        try {
            outcome = driveImportService.recordPicks(pending.workspaceId(), userId, drive, ids);
        } catch (ConnectionNotFoundException e) {
            // Disconnected while the pick was being recorded, which also took back anything it had added.
            return failed(pending.returnTo(), pending, "not_connected");
        }
        String stopped = outcome.stoppedBy() == null ? "" : outcome.stoppedBy().name().toLowerCase(Locale.ROOT);
        log.info("Google's Drive picker returned {} ids: {} on the list, {} not a kind Brownie reads, {} unavailable, {} unchecked, {} over the limit{}.",
                ids.size(), outcome.added(), outcome.unsupported(), outcome.unavailable(), outcome.unchecked(), outcome.overLimit(),
                stopped.isEmpty() ? "" : "; stopped (" + stopped + ")");
        return redirect(pending.returnTo(), "google=picked&access=" + accessWord(pending)
                + "&added=" + outcome.added()
                + "&unsupported=" + outcome.unsupported()
                + "&unavailable=" + outcome.unavailable()
                + "&unchecked=" + outcome.unchecked()
                + "&over_limit=" + outcome.overLimit()
                + (stopped.isEmpty() ? "" : "&stopped=" + stopped));
    }

    /** The reason is one of a fixed set or a plain word Google chose, so it is the one thing about a failure that is logged. */
    private ResponseEntity<Void> failed(String returnTo, PendingConsent pending, String reason) {
        log.info("Google consent{} did not complete ({}).", pending == null ? "" : " for " + pending.access(), reason);
        String access = pending == null ? "" : "&access=" + accessWord(pending);
        return redirect(returnTo, "google=failed" + access + "&reason=" + reason);
    }

    private ResponseEntity<Void> redirect(String returnTo, String query) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(webOrigin + returnTo + "?" + query))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static String accessWord(PendingConsent pending) {
        return pending.access().name().toLowerCase(Locale.ROOT);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }
}
