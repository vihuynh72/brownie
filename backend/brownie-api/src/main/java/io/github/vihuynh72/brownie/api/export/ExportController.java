package io.github.vihuynh72.brownie.api.export;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.export.ExportApproval;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportNotApprovedException;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.export.ExportReceiptNotFoundException;
import io.github.vihuynh72.brownie.core.export.ExportService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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

import java.time.OffsetDateTime;

/**
 * Approves an already-validated revision for export. Not idempotency-key
 * guarded, the same deliberate, already-precedented gap {@code
 * CompilationController} names for its own {@code /compile} route: a
 * retried request produces another approval row rather than replaying the
 * first one, harmless here since every approval names the same still-
 * current revision/manifest and {@code findLatest} always resolves to the
 * newest.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}")
class ExportController {

    private final ExportService exportService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    ExportController(
            ExportService exportService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.exportService = exportService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping("/export-approval")
    @ResponseStatus(HttpStatus.CREATED)
    ExportApprovalResponse approve(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody ApproveExportRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        ExportApproval approval = exportService.approve(
                workspaceId, userId, documentId,
                positive(request.validationManifestId(), "validationManifestId"),
                requireFormat(request.format()));
        return ExportApprovalResponse.from(approval);
    }

    @GetMapping("/export-approval")
    ExportApprovalResponse latest(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return exportService
                .findLatestApproval(workspaceId, userId, documentId)
                .map(ExportApprovalResponse::from)
                .orElseThrow(() -> new ExportNotApprovedException(documentId));
    }

    @PostMapping("/export")
    @ResponseStatus(HttpStatus.CREATED)
    ExportReceiptResponse export(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return ExportReceiptResponse.from(exportService.export(workspaceId, userId, documentId));
    }

    @GetMapping("/export-receipt")
    ExportReceiptResponse latestReceipt(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return exportService
                .findLatestReceipt(workspaceId, userId, documentId)
                .map(ExportReceiptResponse::from)
                .orElseThrow(() -> new ExportReceiptNotFoundException(documentId));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    private static long positive(long value, String field) {
        if (value <= 0) {
            throw new ExportRequestValidationException(field + " must be positive.");
        }
        return value;
    }

    private static ExportFormat requireFormat(String value) {
        if (value == null || value.isBlank()) {
            throw new ExportRequestValidationException("format must not be blank.");
        }
        try {
            return ExportFormat.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ExportRequestValidationException("Unrecognized export format " + value + ".");
        }
    }

    record ApproveExportRequest(long validationManifestId, String format) {
    }

    record ExportReceiptResponse(
            long id,
            long documentId,
            long revisionId,
            long templateVersionId,
            long exportApprovalId,
            long validationManifestId,
            long docxArtifactId,
            String docxSha256,
            Long pdfArtifactId,
            String pdfSha256,
            String format,
            boolean isCompletePair,
            OffsetDateTime exportedAt) {

        static ExportReceiptResponse from(ExportReceipt receipt) {
            return new ExportReceiptResponse(
                    receipt.id(),
                    receipt.documentId(),
                    receipt.revisionId(),
                    receipt.templateVersionId(),
                    receipt.exportApprovalId(),
                    receipt.validationManifestId(),
                    receipt.docxArtifactId(),
                    receipt.docxSha256(),
                    receipt.pdfArtifactId(),
                    receipt.pdfSha256(),
                    receipt.format().name(),
                    receipt.isCompletePair(),
                    receipt.exportedAt());
        }
    }

    record ExportApprovalResponse(
            long id,
            long documentId,
            long revisionId,
            long templateVersionId,
            long validationManifestId,
            String format,
            OffsetDateTime approvedAt) {

        static ExportApprovalResponse from(ExportApproval approval) {
            return new ExportApprovalResponse(
                    approval.id(),
                    approval.documentId(),
                    approval.revisionId(),
                    approval.templateVersionId(),
                    approval.validationManifestId(),
                    approval.format().name(),
                    approval.approvedAt());
        }
    }
}
