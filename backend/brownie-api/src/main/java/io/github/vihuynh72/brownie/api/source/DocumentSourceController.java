package io.github.vihuynh72.brownie.api.source;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.source.DocumentEvidence;
import io.github.vihuynh72.brownie.core.source.DocumentSourceService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A document's own sources: the notes and transcripts that belong to it,
 * which the workspace lists after a reload and Assist extracts from, and
 * the excerpts its values cite from them. The workspace-level snapshot
 * behind each one is still {@code SourceController}'s; this controller
 * adds and reads the document's link to it.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}")
class DocumentSourceController {

    private final DocumentSourceService documentSourceService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    DocumentSourceController(
            DocumentSourceService documentSourceService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.documentSourceService = documentSourceService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping("/sources")
    List<DocumentSourceResponse> list(
            @PathVariable long workspaceId, @PathVariable long documentId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return documentSourceService.list(workspaceId, userId, documentId).stream().map(DocumentSourceResponse::from).toList();
    }

    /** Attaches a READY artifact to this document as a source, or returns the link that already does. */
    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentSourceResponse attach(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody AttachRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        if (request.artifactId() <= 0) {
            throw new DocumentSourceRequestValidationException("artifactId must be positive.");
        }
        return DocumentSourceResponse.from(documentSourceService.attach(workspaceId, userId, documentId, request.artifactId()));
    }

    /** The excerpt a value's evidence marker cites -- 404 unless the span cites one of this document's own sources. */
    @GetMapping("/evidence/{spanId}")
    EvidenceExcerptResponse excerpt(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long spanId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return EvidenceExcerptResponse.from(documentSourceService.excerpt(workspaceId, userId, documentId, spanId));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    record AttachRequest(long artifactId) {
    }

    /** {@code locatorType} names the source format the span was cited in (DOCX, PDF, PLAIN_TEXT); no page or position on the compiled preview is known. */
    record EvidenceExcerptResponse(
            long spanId, long sourceSnapshotId, long sourceArtifactId, String displayFilename, String locatorType, String excerptText) {
        static EvidenceExcerptResponse from(DocumentEvidence evidence) {
            return new EvidenceExcerptResponse(
                    evidence.span().id(),
                    evidence.snapshot().id(),
                    evidence.snapshot().artifactId(),
                    evidence.displayFilename(),
                    switch (evidence.span().locator()) {
                        case io.github.vihuynh72.brownie.core.evidence.EvidenceLocator.Docx ignored -> "DOCX";
                        case io.github.vihuynh72.brownie.core.evidence.EvidenceLocator.Pdf ignored -> "PDF";
                        case io.github.vihuynh72.brownie.core.evidence.EvidenceLocator.PlainText ignored -> "PLAIN_TEXT";
                    },
                    evidence.excerptText());
        }
    }
}
