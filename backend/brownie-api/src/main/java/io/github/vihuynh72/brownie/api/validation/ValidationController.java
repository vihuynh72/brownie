package io.github.vihuynh72.brownie.api.validation;

import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationManifestNotFoundException;
import io.github.vihuynh72.brownie.core.validation.ValidationService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Runs the deterministic validation pipeline against a document's current
 * revision and reports the resulting manifest. Appends a new revision (see
 * {@link ValidationService}'s own javadoc), so this sits at the document
 * level and takes {@code expectedRevisionId} in the body -- the same
 * optimistic-concurrency shape {@code DocumentController#applyEdits}
 * already uses -- rather than under a fixed {@code revisions/{revisionId}}
 * path that the call itself would immediately advance past.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}")
class ValidationController {

    private final ValidationService validationService;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    ValidationController(
            ValidationService validationService,
            CanonicalRequestHasher canonicalRequestHasher,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.validationService = validationService;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping("/validate")
    @ResponseStatus(HttpStatus.CREATED)
    ValidationManifestResponse validate(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody ValidateDocumentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        ValidationManifest manifest = validationService.validate(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new ValidateDocumentHashInput(
                        "document.validate", workspaceId, documentId, request)),
                documentId,
                positive(request.expectedRevisionId(), "expectedRevisionId"));
        return ValidationManifestResponse.from(manifest);
    }

    @GetMapping("/revisions/{revisionId}/validation")
    ValidationManifestResponse latest(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long revisionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return validationService
                .findLatest(workspaceId, userId, documentId, revisionId)
                .map(ValidationManifestResponse::from)
                .orElseThrow(() -> new ValidationManifestNotFoundException(documentId, revisionId));
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

    private static long positive(long value, String field) {
        if (value <= 0) {
            throw new ValidationRequestValidationException(field + " must be positive.");
        }
        return value;
    }

    private static IdempotencyKey requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new ValidationRequestValidationException("Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        return new IdempotencyKey(value);
    }

    record ValidateDocumentRequest(long expectedRevisionId) {
    }

    private record ValidateDocumentHashInput(String operation, long workspaceId, long documentId, ValidateDocumentRequest request) {
    }

    record ValidationManifestResponse(
            long id,
            long documentId,
            long revisionId,
            long templateId,
            long templateVersionId,
            long docxArtifactId,
            String docxSha256,
            Long pdfArtifactId,
            String pdfSha256,
            List<ValidationFindingResponse> findings,
            boolean hasUnresolvedBlocking,
            OffsetDateTime createdAt) {

        static ValidationManifestResponse from(ValidationManifest manifest) {
            return new ValidationManifestResponse(
                    manifest.id(),
                    manifest.documentId(),
                    manifest.revisionId(),
                    manifest.templateId(),
                    manifest.templateVersionId(),
                    manifest.docxArtifactId(),
                    manifest.docxSha256(),
                    manifest.pdfArtifactId(),
                    manifest.pdfSha256(),
                    manifest.findings().stream().map(ValidationFindingResponse::from).toList(),
                    manifest.hasUnresolvedBlocking(),
                    manifest.createdAt());
        }
    }

    record ValidationFindingResponse(String code, String severity, String fieldId, String message) {

        static ValidationFindingResponse from(ValidationFinding finding) {
            return new ValidationFindingResponse(finding.code().name(), finding.severity().name(), finding.fieldId(), finding.message());
        }
    }
}
