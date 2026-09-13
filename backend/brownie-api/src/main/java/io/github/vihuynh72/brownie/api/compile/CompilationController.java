package io.github.vihuynh72.brownie.api.compile;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.compile.CompilationManifest;
import io.github.vihuynh72.brownie.core.compile.CompilationNotFoundException;
import io.github.vihuynh72.brownie.core.compile.CompilationService;
import io.github.vihuynh72.brownie.core.compile.IntegrityFinding;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Compiles one exact, already-persisted document revision into a filled
 * DOCX and rendered PDF, deterministically and without any model call. Not
 * yet idempotency-key guarded, unlike the document-mutation routes: a
 * retried request produces another compilation and another pair of
 * artifacts rather than replaying the first one. This is a deliberate,
 * named gap for this bounded capability, not an oversight -- the fuller
 * export flow this plan describes (bound to a review decision, with its
 * own idempotent request lifecycle) belongs to later work.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/revisions/{revisionId}")
class CompilationController {

    private final CompilationService compilationService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    CompilationController(
            CompilationService compilationService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.compilationService = compilationService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping("/compile")
    @ResponseStatus(HttpStatus.CREATED)
    CompilationManifestResponse compile(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long revisionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return CompilationManifestResponse.from(compilationService.compile(workspaceId, userId, documentId, revisionId));
    }

    @GetMapping("/compilation")
    CompilationManifestResponse latest(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long revisionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return compilationService
                .findLatest(workspaceId, userId, documentId, revisionId)
                .map(CompilationManifestResponse::from)
                .orElseThrow(() -> new CompilationNotFoundException(documentId, revisionId));
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

    record CompilationManifestResponse(
            long id,
            long documentId,
            long revisionId,
            long templateId,
            long templateVersionId,
            long docxArtifactId,
            String docxSha256,
            long pdfArtifactId,
            String pdfSha256,
            String rendererVersion,
            List<IntegrityFindingResponse> integrityFindings,
            boolean allIntegrityChecksPassed,
            OffsetDateTime compiledAt) {

        static CompilationManifestResponse from(CompilationManifest manifest) {
            return new CompilationManifestResponse(
                    manifest.id(),
                    manifest.documentId(),
                    manifest.revisionId(),
                    manifest.templateId(),
                    manifest.templateVersionId(),
                    manifest.docxArtifactId(),
                    manifest.docxSha256(),
                    manifest.pdfArtifactId(),
                    manifest.pdfSha256(),
                    manifest.rendererVersion(),
                    manifest.integrityFindings().stream().map(IntegrityFindingResponse::from).toList(),
                    manifest.allIntegrityChecksPassed(),
                    manifest.compiledAt());
        }
    }

    record IntegrityFindingResponse(String fieldId, String expectedText, boolean foundInDocx, boolean foundInPdf) {

        static IntegrityFindingResponse from(IntegrityFinding finding) {
            return new IntegrityFindingResponse(finding.fieldId(), finding.expectedText(), finding.foundInDocx(), finding.foundInPdf());
        }
    }
}
