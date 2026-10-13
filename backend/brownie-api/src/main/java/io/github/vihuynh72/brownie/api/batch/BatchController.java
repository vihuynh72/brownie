package io.github.vihuynh72.brownie.api.batch;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStorageException;
import io.github.vihuynh72.brownie.core.batch.BatchCreateRequest;
import io.github.vihuynh72.brownie.core.batch.BatchExportCreateRequest;
import io.github.vihuynh72.brownie.core.batch.BatchExportZip;
import io.github.vihuynh72.brownie.core.batch.BatchService;
import io.github.vihuynh72.brownie.core.batch.BatchZipWriter;
import io.github.vihuynh72.brownie.core.batch.FieldMapping;
import io.github.vihuynh72.brownie.core.batch.FieldMappingMode;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The batch routes: make one from a template and a table, watch its rows,
 * fill it a step at a time, or remove it. Creating and filling touch an
 * upload and a template, so both capabilities are required -- the same
 * pairing the fillable-form route already uses; reads run the same
 * workspace check every other read route runs, deletes need a manage
 * capability, and the delete routine checks membership for itself.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/batches")
class BatchController {

    private final BatchService batchService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    BatchController(
            BatchService batchService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.batchService = batchService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BatchResponse create(
            @PathVariable long workspaceId,
            @RequestBody CreateBatchRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        try {
            var batch = batchService.create(workspaceId, userId, toDomain(request, idempotencyKey));
            return BatchResponse.from(batch, batchService.find(workspaceId, userId, batch.id()).rows());
        } catch (IOException e) {
            throw new ArtifactStorageException("The batch's spreadsheet could not be read.", e);
        }
    }

    @PostMapping("/{batchId}/fill")
    BatchStepResponse fill(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        try {
            return BatchStepResponse.from(batchService.fill(workspaceId, userId, batchId));
        } catch (IOException e) {
            throw new ArtifactStorageException("The batch's spreadsheet could not be read.", e);
        }
    }

    @PostMapping("/{batchId}/accept")
    BatchAcceptResponse accept(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireBatchWriteAccess(userId, workspaceId);
        return BatchAcceptResponse.from(batchService.accept(workspaceId, userId, batchId));
    }

    @PostMapping("/{batchId}/check")
    BatchStepResponse check(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireBatchWriteAccess(userId, workspaceId);
        return BatchStepResponse.from(batchService.check(workspaceId, userId, batchId));
    }

    @PostMapping("/{batchId}/compose")
    BatchStepResponse compose(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireBatchWriteAccess(userId, workspaceId);
        try {
            return BatchStepResponse.from(batchService.compose(workspaceId, userId, batchId));
        } catch (IOException e) {
            throw new ArtifactStorageException("The batch's spreadsheet could not be read.", e);
        }
    }

    @PostMapping("/{batchId}/exports")
    @ResponseStatus(HttpStatus.CREATED)
    BatchExportResponse createExport(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @RequestBody(required = false) CreateBatchExportRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireBatchWriteAccess(userId, workspaceId);
        if (request == null || request.format() == null || request.format().isBlank()) {
            throw new BatchRequestValidationException("An export request needs a format.");
        }
        ExportFormat format;
        try {
            format = ExportFormat.valueOf(request.format().trim());
        } catch (IllegalArgumentException e) {
            throw new BatchRequestValidationException(
                    "format must be one of DOCX, PDF or BOTH, not \"" + request.format() + "\".");
        }
        return BatchExportResponse.from(batchService.createExport(
                workspaceId, userId, batchId,
                new BatchExportCreateRequest(format, requireIdempotencyKey(idempotencyKey))));
    }

    @PostMapping("/{batchId}/exports/{exportId}/advance")
    BatchExportStepResponse advanceExport(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @PathVariable long exportId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireBatchWriteAccess(userId, workspaceId);
        return BatchExportStepResponse.from(batchService.advanceExport(workspaceId, userId, batchId, exportId));
    }

    @GetMapping("/{batchId}/exports/{exportId}")
    BatchExportResponse findExport(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @PathVariable long exportId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return BatchExportResponse.from(batchService.findExport(workspaceId, userId, batchId, exportId));
    }

    /**
     * The archive of one finished run. The plan is built while the
     * request still holds its tenant context; the bytes themselves stream
     * afterwards, each verified against its receipt before it is written,
     * so a tampered blob aborts the download rather than being served.
     */
    @GetMapping("/{batchId}/exports/{exportId}/download")
    ResponseEntity<StreamingResponseBody> downloadExport(
            @PathVariable long workspaceId,
            @PathVariable long batchId,
            @PathVariable long exportId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        BatchExportZip zip = batchService.prepareExportZip(workspaceId, userId, batchId, exportId);
        String filename = BatchZipWriter.zipFilename(zip.batchTitle());
        StreamingResponseBody body = out -> batchService.writeExportZip(workspaceId, userId, zip, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(body);
    }

    @GetMapping
    List<BatchSummaryResponse> findAll(@PathVariable long workspaceId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return batchService.list(workspaceId, userId).stream().map(BatchSummaryResponse::from).toList();
    }

    @GetMapping("/{batchId}")
    BatchResponse find(@PathVariable long workspaceId, @PathVariable long batchId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        var detail = batchService.find(workspaceId, userId, batchId);
        return BatchResponse.from(detail.batch(), detail.rows());
    }

    @DeleteMapping("/{batchId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long workspaceId, @PathVariable long batchId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        batchService.delete(workspaceId, userId, batchId);
    }

    private static BatchCreateRequest toDomain(CreateBatchRequest request, String idempotencyKey) {
        if (request == null) {
            throw new BatchRequestValidationException("A batch request body is required.");
        }
        List<FieldMapping> mapping = request.mapping() == null ? List.of() : request.mapping().stream()
                .map(entry -> new FieldMapping(
                        requireText(entry.fieldId(), "mapping.fieldId"),
                        requireColumn(entry.column()),
                        entry.mode() == null ? FieldMappingMode.VALUE : entry.mode()))
                .toList();
        return new BatchCreateRequest(
                positive(request.templateId(), "templateId"),
                positive(request.templateVersionId(), "templateVersionId"),
                positive(request.artifactId(), "artifactId"),
                request.sheet(),
                request.hasHeaderRow(),
                request.dateOrder(),
                mapping,
                request.titleColumn(),
                requireIdempotencyKey(idempotencyKey));
    }

    private static long positive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BatchRequestValidationException(field + " must be a positive id.");
        }
        return value;
    }

    private static int requireColumn(Integer value) {
        if (value == null || value < 0) {
            throw new BatchRequestValidationException("mapping.column must be a column index, zero or above.");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BatchRequestValidationException(field + " must not be blank.");
        }
        return value;
    }

    private static IdempotencyKey requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new BatchRequestValidationException(
                    "Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        return new IdempotencyKey(value);
    }

    /** The capability pair every batch write already asks for: it reads a source artifact and writes a template's documents. */
    private void requireBatchWriteAccess(long userId, long workspaceId) {
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
    }

    private void requireAccess(long userId, long workspaceId) {
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
    }

    private long currentUserId(OidcUser principal) {
        return userIdentityRepository
                .findByIssuerAndSubject(principal.getIssuer().toString(), principal.getSubject())
                .orElseThrow(AuthenticatedIdentityMissingException::new)
                .id();
    }
}
