package io.github.vihuynh72.brownie.api.document;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionNotFoundException;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Triggers DOCX structural extraction on a READY artifact and reads back
 * its result. Deliberately minimal: nothing downstream consumes this yet
 * (template teaching and generation, which will, do not exist), so this is
 * a small, real, working surface rather than a placeholder -- extended
 * when an actual caller needs more than trigger-and-read.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/artifacts/{artifactId}/extraction")
class DocxExtractionController {

    private final DocumentExtractionService documentExtractionService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    DocxExtractionController(
            DocumentExtractionService documentExtractionService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.documentExtractionService = documentExtractionService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /**
     * Always returns 200 with whatever terminal outcome the extraction
     * actually reached -- COMPLETE, UNSUPPORTED, or FAILED are all real,
     * successfully persisted answers, not error responses. A real HTTP
     * error means the request itself could not even be attempted: the
     * artifact does not exist (404), is not READY yet (409), or is READY
     * but not a DOCX (415).
     */
    @PostMapping
    ExtractionResponse extract(@PathVariable long workspaceId, @PathVariable long artifactId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        return ExtractionResponse.from(documentExtractionService.extractDocx(workspaceId, userId, artifactId));
    }

    @GetMapping
    ExtractionResponse latest(@PathVariable long workspaceId, @PathVariable long artifactId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        return documentExtractionService
                .findLatest(workspaceId, userId, artifactId)
                .map(ExtractionResponse::from)
                .orElseThrow(() -> new ExtractionVersionNotFoundException(artifactId));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no recorded identity for issuer/subject " + issuer + "/" + subject))
                .id();
    }

    record FeatureFindingResponse(String feature, String location, String detail) {
        static FeatureFindingResponse from(DocxFeatureFinding finding) {
            return new FeatureFindingResponse(finding.feature().name(), finding.location(), finding.detail());
        }
    }

    record ExtractionResponse(
            long id, String parserVersion, String status, List<FeatureFindingResponse> unsupportedFeatures, String failureReason) {
        static ExtractionResponse from(ExtractionVersion version) {
            List<FeatureFindingResponse> findings = version.featureReport() == null
                    ? List.of()
                    : version.featureReport().findings().stream().map(FeatureFindingResponse::from).toList();
            return new ExtractionResponse(
                    version.id(), version.parserVersion(), version.status().name(), findings, version.failureReason());
        }
    }
}
