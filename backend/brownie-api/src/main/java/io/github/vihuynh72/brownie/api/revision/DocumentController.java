package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
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
import io.github.vihuynh72.brownie.core.revision.FieldItemRef;
import io.github.vihuynh72.brownie.core.revision.FieldState;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.LockState;
import io.github.vihuynh72.brownie.core.revision.ReviewState;
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
                request.toEvidence(),
                requireText(request.initialRevisionReason(), "initialRevisionReason"));
        return documentResponse(workspaceId, userId, mutation.document());
    }

    /**
     * A lightweight summary per document, deliberately without its current
     * revision's full field content -- a list view for choosing which
     * document to open, not a place to review one. Not paginated: a
     * personal workspace's document count stays small enough that a cursor
     * here now would be speculative machinery ahead of any measured need.
     */
    @GetMapping
    List<DocumentSummaryResponse> findAll(@PathVariable long workspaceId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        return revisionService.findAllDocuments(workspaceId, userId).stream().map(DocumentSummaryResponse::from).toList();
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
                request.toEvidence(),
                requireText(request.editReason(), "editReason"));
        return DocumentRevisionResponse.from(mutation.revision());
    }

    /** Independent of every other dimension -- see {@code FieldState}'s own javadoc; the field's value, evidence, authorship, and lock are left exactly as they were. */
    @PostMapping("/{documentId}/fields/review-decision")
    DocumentRevisionResponse recordReviewDecision(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody RecordReviewDecisionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        DocumentMutationResult mutation = revisionService.recordReviewDecision(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new RecordReviewDecisionHashInput(
                        "document.record-review-decision", workspaceId, documentId, request)),
                documentId,
                positive(request.expectedRevisionId(), "expectedRevisionId"),
                request.toRef(),
                request.toReviewState(),
                blankToDefault(request.editReason(), "Recorded a review decision."));
        return DocumentRevisionResponse.from(mutation.revision());
    }

    /** The only route that ever changes a field's lock deliberately -- see {@code RevisionService#setFieldLock}'s own javadoc. */
    @PostMapping("/{documentId}/fields/lock")
    DocumentRevisionResponse setFieldLock(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody SetFieldLockRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        DocumentMutationResult mutation = revisionService.setFieldLock(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new SetFieldLockHashInput(
                        "document.set-field-lock", workspaceId, documentId, request)),
                documentId,
                positive(request.expectedRevisionId(), "expectedRevisionId"),
                request.toRef(),
                request.toLockState(),
                blankToDefault(request.editReason(), "Changed the field's lock state."));
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
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
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

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
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

    private record RecordReviewDecisionHashInput(String operation, long workspaceId, long documentId, RecordReviewDecisionRequest request) {
    }

    private record SetFieldLockHashInput(String operation, long workspaceId, long documentId, SetFieldLockRequest request) {
    }

    record RecordReviewDecisionRequest(long expectedRevisionId, String fieldId, Integer itemIndex, String decision, String editReason) {

        FieldItemRef toRef() {
            return toFieldItemRef(fieldId, itemIndex);
        }

        ReviewState toReviewState() {
            String value = requireText(decision, "decision");
            try {
                return ReviewState.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new DocumentRequestValidationException("decision must be one of " + java.util.Arrays.toString(ReviewState.values()) + ".");
            }
        }
    }

    record SetFieldLockRequest(long expectedRevisionId, String fieldId, Integer itemIndex, String lock, String editReason) {

        FieldItemRef toRef() {
            return toFieldItemRef(fieldId, itemIndex);
        }

        LockState toLockState() {
            String value = requireText(lock, "lock");
            try {
                return LockState.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new DocumentRequestValidationException("lock must be one of " + java.util.Arrays.toString(LockState.values()) + ".");
            }
        }
    }

    private static FieldItemRef toFieldItemRef(String fieldId, Integer itemIndex) {
        requireText(fieldId, "fieldId");
        if (itemIndex != null && itemIndex < 0) {
            throw new DocumentRequestValidationException("itemIndex must not be negative when present.");
        }
        return itemIndex == null ? FieldItemRef.scalar(fieldId) : FieldItemRef.item(fieldId, itemIndex);
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

        Map<String, List<Long>> toEvidence() {
            Map<String, List<Long>> evidence = new LinkedHashMap<>();
            for (Map.Entry<String, FieldValueRequest> field : fields.entrySet()) {
                List<Long> spanIds = field.getValue() == null ? null : field.getValue().evidenceSourceSpanIds();
                if (spanIds != null && !spanIds.isEmpty()) {
                    evidence.put(field.getKey(), spanIds);
                }
            }
            return evidence;
        }
    }

    record PatchDocumentContentRequest(long expectedRevisionId, List<FieldEditRequest> edits, String editReason) {

        List<DocumentFieldEdit> toEdits() {
            if (edits == null || edits.isEmpty()) {
                throw new DocumentRequestValidationException("edits must contain at least one typed field command.");
            }
            return edits.stream().map(FieldEditRequest::toDomain).toList();
        }

        Map<String, List<Long>> toEvidence() {
            Map<String, List<Long>> evidence = new LinkedHashMap<>();
            for (FieldEditRequest edit : edits) {
                List<Long> spanIds = edit.value() == null ? null : edit.value().evidenceSourceSpanIds();
                if (spanIds != null && !spanIds.isEmpty()) {
                    evidence.put(edit.fieldId(), spanIds);
                }
            }
            return evidence;
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

    record FieldValueRequest(String type, String cardinality, String value, List<String> values, List<Long> evidenceSourceSpanIds) {
    }

    record DocumentSummaryResponse(
            long id, String title, long templateId, long templateVersionId, long currentRevisionId, OffsetDateTime createdAt) {
        static DocumentSummaryResponse from(Document document) {
            return new DocumentSummaryResponse(
                    document.id(), document.title(), document.templateId(), document.templateVersionId(),
                    document.currentRevisionId(), document.createdAt());
        }
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
            revision.content().fields().forEach((fieldId, value) -> fields.put(
                    fieldId,
                    FieldValueResponse.from(
                            fieldId, value, revision.evidence().getOrDefault(fieldId, List.of()), revision.fieldStates())));
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

    record FieldValueResponse(
            String type,
            String cardinality,
            String value,
            List<String> values,
            List<Long> evidenceSourceSpanIds,
            FieldStateResponse fieldState,
            List<FieldStateResponse> itemFieldStates) {

        static FieldValueResponse from(
                String fieldId, FieldValue value, List<Long> evidenceSourceSpanIds, Map<FieldItemRef, FieldState> fieldStates) {
            return switch (value) {
                case FieldValue.TextValue(String text) -> new FieldValueResponse(
                        "TEXT", "SCALAR", text, null, evidenceSourceSpanIds,
                        stateOf(fieldStates, FieldItemRef.scalar(fieldId)), null);
                case FieldValue.DateValue(LocalDate date) -> new FieldValueResponse(
                        "DATE", "SCALAR", date.toString(), null, evidenceSourceSpanIds,
                        stateOf(fieldStates, FieldItemRef.scalar(fieldId)), null);
                case FieldValue.RepeatedTextValue(List<String> texts) -> new FieldValueResponse(
                        "TEXT", "REPEATED", null, texts, evidenceSourceSpanIds, null, itemStatesOf(fieldStates, fieldId, texts.size()));
                case FieldValue.RepeatedDateValue(List<LocalDate> dates) -> new FieldValueResponse(
                        "DATE", "REPEATED", null, dates.stream().map(LocalDate::toString).toList(), evidenceSourceSpanIds,
                        null, itemStatesOf(fieldStates, fieldId, dates.size()));
            };
        }

        private static FieldStateResponse stateOf(Map<FieldItemRef, FieldState> fieldStates, FieldItemRef ref) {
            FieldState state = fieldStates.get(ref);
            return state == null ? null : FieldStateResponse.from(state);
        }

        private static List<FieldStateResponse> itemStatesOf(
                Map<FieldItemRef, FieldState> fieldStates, String fieldId, int itemCount) {
            List<FieldStateResponse> states = new java.util.ArrayList<>(itemCount);
            for (int index = 0; index < itemCount; index++) {
                states.add(stateOf(fieldStates, FieldItemRef.item(fieldId, index)));
            }
            return states;
        }
    }

    record FieldStateResponse(String authorship, String evidenceSupport, String validation, String review, String lock) {

        static FieldStateResponse from(FieldState state) {
            return new FieldStateResponse(
                    state.authorship().name(),
                    state.evidenceSupport().name(),
                    state.validation().name(),
                    state.review().name(),
                    state.lock().name());
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
