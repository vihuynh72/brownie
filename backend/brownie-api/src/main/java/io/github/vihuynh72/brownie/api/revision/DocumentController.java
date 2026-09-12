package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.Document;
import io.github.vihuynh72.brownie.core.revision.DocumentCommandType;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentFieldEdit;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.DocumentRevision;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The document surface accepts only explicit, typed field changes. It never
 * accepts an arbitrary JSON patch or permits edits to a prior revision.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents")
class DocumentController {

    private final RevisionService revisionService;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    DocumentController(
            RevisionService revisionService,
            CanonicalRequestHasher canonicalRequestHasher,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.revisionService = revisionService;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DocumentResponse create(
            @PathVariable long workspaceId,
            @RequestBody CreateDocumentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        DocumentMutationResult mutation = revisionService.createDocument(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new CreateDocumentHashInput(
                        DocumentCommandType.CREATE.operation(), workspaceId, request)),
                requireText(request.title(), "title"),
                positive(request.templateId(), "templateId"),
                positive(request.templateVersionId(), "templateVersionId"),
                request.toContent(),
                requireText(request.initialRevisionReason(), "initialRevisionReason"));
        return documentResponse(workspaceId, userId, mutation.document());
    }

    @GetMapping("/{documentId}")
    DocumentResponse find(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return documentResponse(workspaceId, userId, requireDocument(workspaceId, userId, documentId));
    }

    @GetMapping("/{documentId}/revisions")
    List<DocumentRevisionResponse> history(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        requireDocument(workspaceId, userId, documentId);
        return revisionService.findHistory(workspaceId, userId, documentId).stream().map(DocumentRevisionResponse::from).toList();
    }

    @GetMapping("/{documentId}/revisions/{revisionId}")
    DocumentRevisionResponse findRevision(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @PathVariable long revisionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        requireDocument(workspaceId, userId, documentId);
        DocumentRevision revision = revisionService.findRevision(workspaceId, userId, documentId, revisionId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return DocumentRevisionResponse.from(revision);
    }

    @PatchMapping("/{documentId}/content")
    DocumentRevisionResponse applyEdits(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody PatchDocumentContentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        DocumentMutationResult mutation = revisionService.applyUserEdits(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new EditDocumentContentHashInput(
                        DocumentCommandType.EDIT_CONTENT.operation(), workspaceId, documentId, request)),
                documentId,
                positive(request.expectedRevisionId(), "expectedRevisionId"),
                request.toEdits(),
                requireText(request.editReason(), "editReason"));
        return DocumentRevisionResponse.from(mutation.revision());
    }

    private DocumentResponse documentResponse(long workspaceId, long userId, Document document) {
        DocumentRevision current = revisionService.findRevision(
                        workspaceId, userId, document.id(), document.currentRevisionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Document " + document.id() + " points to a missing current revision."));
        return DocumentResponse.from(document, current);
    }

    private Document requireDocument(long workspaceId, long userId, long documentId) {
        if (documentId <= 0) {
            throw new DocumentRequestValidationException("documentId must be positive.");
        }
        return revisionService.findDocument(workspaceId, userId, documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private void requireAccess(long userId, long workspaceId) {
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
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
            throw new DocumentRequestValidationException(field + " must be positive.");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DocumentRequestValidationException(field + " must not be blank.");
        }
        return value;
    }

    private static IdempotencyKey requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new DocumentRequestValidationException(
                    "Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        return new IdempotencyKey(value);
    }

    private record CreateDocumentHashInput(String operation, long workspaceId, CreateDocumentRequest request) {
    }

    private record EditDocumentContentHashInput(
            String operation,
            long workspaceId,
            long documentId,
            PatchDocumentContentRequest request) {
    }

    record CreateDocumentRequest(
            String title,
            long templateId,
            long templateVersionId,
            Map<String, FieldValueRequest> fields,
            String initialRevisionReason) {

        DocumentContent toContent() {
            if (fields == null) {
                throw new DocumentRequestValidationException("fields must be an object, including an empty object when appropriate.");
            }
            Map<String, FieldValue> typed = new LinkedHashMap<>();
            for (Map.Entry<String, FieldValueRequest> field : fields.entrySet()) {
                String fieldId = requireText(field.getKey(), "field ID");
                if (typed.putIfAbsent(fieldId, requireFieldValue(field.getValue(), fieldId)) != null) {
                    throw new DocumentRequestValidationException("A field can appear at most once in document content.");
                }
            }
            return new DocumentContent(typed);
        }
    }

    record PatchDocumentContentRequest(long expectedRevisionId, List<FieldEditRequest> edits, String editReason) {

        List<DocumentFieldEdit> toEdits() {
            if (edits == null || edits.isEmpty()) {
                throw new DocumentRequestValidationException("edits must contain at least one typed field command.");
            }
            return edits.stream().map(FieldEditRequest::toDomain).toList();
        }
    }

    record FieldEditRequest(String operation, String fieldId, FieldValueRequest value) {

        DocumentFieldEdit toDomain() {
            String targetField = requireText(fieldId, "fieldId");
            if (operation == null) {
                throw new DocumentRequestValidationException("operation is required for every field edit.");
            }
            return switch (operation) {
                case "SET" -> new DocumentFieldEdit.SetValue(targetField, requireFieldValue(value, targetField));
                case "CLEAR" -> {
                    if (value != null) {
                        throw new DocumentRequestValidationException("A CLEAR edit must not include a value.");
                    }
                    yield new DocumentFieldEdit.ClearValue(targetField);
                }
                default -> throw new DocumentRequestValidationException("operation must be SET or CLEAR.");
            };
        }
    }

    record FieldValueRequest(String type, String cardinality, String value, List<String> values) {
    }

    record DocumentResponse(
            long id,
            String title,
            long templateId,
            long templateVersionId,
            long currentRevisionId,
            OffsetDateTime createdAt,
            DocumentRevisionResponse currentRevision) {

        static DocumentResponse from(Document document, DocumentRevision currentRevision) {
            return new DocumentResponse(
                    document.id(),
                    document.title(),
                    document.templateId(),
                    document.templateVersionId(),
                    document.currentRevisionId(),
                    document.createdAt(),
                    DocumentRevisionResponse.from(currentRevision));
        }
    }

    record DocumentRevisionResponse(
            long id,
            int revisionNumber,
            Long parentRevisionId,
            Map<String, FieldValueResponse> fields,
            String contentHash,
            String editReason,
            OffsetDateTime createdAt) {

        static DocumentRevisionResponse from(DocumentRevision revision) {
            Map<String, FieldValueResponse> fields = new LinkedHashMap<>();
            revision.content().fields().forEach((fieldId, value) -> fields.put(fieldId, FieldValueResponse.from(value)));
            return new DocumentRevisionResponse(
                    revision.id(),
                    revision.revisionNumber(),
                    revision.parentRevisionId(),
                    Map.copyOf(fields),
                    revision.contentHash(),
                    revision.editReason(),
                    revision.createdAt());
        }
    }

    record FieldValueResponse(String type, String cardinality, String value, List<String> values) {

        static FieldValueResponse from(FieldValue value) {
            return switch (value) {
                case FieldValue.TextValue(String text) -> new FieldValueResponse("TEXT", "SCALAR", text, null);
                case FieldValue.DateValue(LocalDate date) -> new FieldValueResponse("DATE", "SCALAR", date.toString(), null);
                case FieldValue.RepeatedTextValue(List<String> texts) -> new FieldValueResponse("TEXT", "REPEATED", null, texts);
                case FieldValue.RepeatedDateValue(List<LocalDate> dates) -> new FieldValueResponse(
                        "DATE", "REPEATED", null, dates.stream().map(LocalDate::toString).toList());
            };
        }
    }

    private static FieldValue requireFieldValue(FieldValueRequest request, String fieldId) {
        if (request == null || request.type() == null || request.cardinality() == null) {
            throw new DocumentRequestValidationException("Field " + fieldId + " requires type and cardinality.");
        }
        return switch (request.type() + ":" + request.cardinality()) {
            case "TEXT:SCALAR" -> new FieldValue.TextValue(requireScalar(request, fieldId));
            case "DATE:SCALAR" -> new FieldValue.DateValue(parseDate(requireScalar(request, fieldId), fieldId));
            case "TEXT:REPEATED" -> new FieldValue.RepeatedTextValue(requireValues(request, fieldId));
            case "DATE:REPEATED" -> new FieldValue.RepeatedDateValue(requireValues(request, fieldId).stream()
                    .map(value -> parseDate(value, fieldId))
                    .toList());
            default -> throw new DocumentRequestValidationException(
                    "Field " + fieldId + " must use one supported type/cardinality combination.");
        };
    }

    private static String requireScalar(FieldValueRequest request, String fieldId) {
        if (request.value() == null || request.values() != null) {
            throw new DocumentRequestValidationException("Scalar field " + fieldId + " requires value and must not include values.");
        }
        return request.value();
    }

    private static List<String> requireValues(FieldValueRequest request, String fieldId) {
        if (request.values() == null || request.value() != null) {
            throw new DocumentRequestValidationException("Repeated field " + fieldId + " requires values and must not include value.");
        }
        if (request.values().stream().anyMatch(value -> value == null)) {
            throw new DocumentRequestValidationException("Repeated field " + fieldId + " cannot contain null values.");
        }
        return List.copyOf(request.values());
    }

    private static LocalDate parseDate(String value, String fieldId) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new DocumentRequestValidationException("DATE field " + fieldId + " must use ISO local-date form.");
        }
    }
}
