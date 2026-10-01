package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.template.CandidateBindingReport;
import io.github.vihuynh72.brownie.core.template.CandidateFieldBinding;
import io.github.vihuynh72.brownie.core.template.DocxControlOrigin;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import io.github.vihuynh72.brownie.core.template.FillSpotReviewService;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.MalformedTemplateRequestException;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionNotFoundException;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Creates a template draft against an already-extracted DOCX source,
 * replaces its field definitions and bindings, activates an immutable
 * version, and moves a template to the Trash Bin and back. The examples and
 * rule-decision endpoints are not exposed here.
 * Deliberately returns the draft version alongside the template on
 * creation, since a caller needs that version's own number for the very
 * next {@code PUT .../draft/bindings} call.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final FillSpotReviewService fillSpotReviewService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    TemplateController(
            TemplateService templateService,
            FillSpotReviewService fillSpotReviewService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.templateService = templateService;
        this.fillSpotReviewService = fillSpotReviewService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /**
     * Every template in the workspace that is not in the Trash Bin, for a
     * person choosing which one to start a new document from --
     * deliberately unfiltered by status (ACTIVE and DRAFT alike), since a
     * caller building a "manage templates" view needs both; a caller
     * building only a document-creation picker filters to {@code
     * currentActiveVersionId != null} itself. With {@code trashed=true} it
     * is the Trash Bin instead: only the trashed ones, the most recently
     * trashed first.
     */
    @GetMapping
    List<TemplateResponse> findAll(
            @PathVariable("workspaceId") long workspaceId,
            @RequestParam(name = "trashed", defaultValue = "false") boolean trashed,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        List<Template> templates =
                trashed ? templateService.findTrashed(workspaceId, userId) : templateService.findAll(workspaceId, userId);
        return templates.stream().map(TemplateResponse::from).toList();
    }

    /**
     * Moves the template to the Trash Bin: it leaves the list new documents
     * are started from, and every document already made from it keeps
     * working. Trashing it again answers with it unchanged.
     */
    @PostMapping("/{templateId}/trash")
    TemplateResponse trash(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return TemplateResponse.from(templateService.trash(workspaceId, userId, templateId));
    }

    /** Takes the template back out of the Trash Bin. Restoring it again answers with it unchanged. */
    @PostMapping("/{templateId}/restore")
    TemplateResponse restore(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return TemplateResponse.from(templateService.restore(workspaceId, userId, templateId));
    }

    /** One template version by its own ID, so a caller can read an ACTIVATED version's field list before creating a document against it. */
    @GetMapping("/{templateId}/versions/{versionId}")
    TemplateVersionResponse findVersion(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @PathVariable("versionId") long versionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return versionResponse(workspaceId, userId, templateService
                .findVersion(workspaceId, userId, templateId, versionId)
                .orElseThrow(() -> new TemplateVersionNotFoundException(templateId, versionId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TemplateDraftResponse create(
            @PathVariable("workspaceId") long workspaceId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody CreateTemplateRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        List<PreparationNotice> notices = request.preparationNotices() == null
                ? null
                : request.preparationNotices().stream().map(PreparationNoticeRequest::toDomainOrRefuse).toList();
        Template template = templateService.createDraft(workspaceId, userId, request.displayName(), request.sourceArtifactId(), notices);
        TemplateVersion draft = templateService
                .findDraftVersion(workspaceId, userId, template.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Template " + template.id() + " has no draft version immediately after creating it."));
        return new TemplateDraftResponse(TemplateResponse.from(template), versionResponse(workspaceId, userId, draft));
    }

    @PutMapping("/{templateId}/draft/bindings")
    TemplateVersionResponse replaceBindings(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody ReplaceBindingsRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        List<FieldDefinition> fields = request.fields().stream().map(FieldDefinitionRequest::toDomain).toList();
        TemplateVersion updated =
                templateService.replaceDraftBindings(workspaceId, userId, templateId, request.expectedVersionNumber(), fields);
        return versionResponse(workspaceId, userId, updated);
    }

    /**
     * The draft's own pinned structural graph, for a person to browse when
     * choosing an exact {@code STRUCTURAL_NODE} to bind a field to -- the
     * same graph {@code PUT .../draft/bindings} validates every binding
     * against. Deliberately serializes the whole node tree, unlike {@code
     * ExtractionController}'s own summary-only response: that response has
     * no caller needing more detail yet, and this one exists specifically
     * so a caller can pick a real {@code nodeId} out of it.
     */
    @GetMapping("/{templateId}/draft/structure")
    DraftStructureResponse draftStructure(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return DraftStructureResponse.from(templateService.findDraftStructuralGraph(workspaceId, userId, templateId));
    }

    /**
     * Candidate field bindings proposed from the draft's own structure -- a
     * starting point offered back for review, never applied by itself. A
     * caller still submits its own chosen fields (accepted as proposed,
     * retyped, renamed, or ignored entirely in favor of manual mapping)
     * through the existing {@code PUT .../draft/bindings} call.
     */
    @GetMapping("/{templateId}/draft/candidate-bindings")
    CandidateBindingReportResponse draftCandidateBindings(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return CandidateBindingReportResponse.from(templateService.proposeCandidateBindings(workspaceId, userId, templateId));
    }

    @PostMapping("/{templateId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    TemplateVersionResponse activate(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody ActivateVersionRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        TemplateVersion activated = templateService.activate(
                workspaceId, userId, templateId, request.expectedVersionNumber(), Boolean.TRUE.equals(request.allowNoPlaces()));
        return versionResponse(workspaceId, userId, activated);
    }

    /**
     * A person saying a spot Brownie found is right: the page stops marking
     * it as found. Keeping it again changes nothing. The review belongs to
     * the template, so it holds in every later version with the field.
     */
    @PutMapping("/{templateId}/fields/{fieldId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reviewField(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @PathVariable("fieldId") String fieldId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody FieldReviewRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        requireKept(request == null ? null : request.decision());
        fillSpotReviewService.keep(workspaceId, userId, templateId, List.of(fieldId));
    }

    /** "Keep all": the same review for several fields at once; all are kept or, if one cannot be, none is. */
    @PostMapping("/{templateId}/field-reviews")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reviewFields(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody FieldReviewsRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        if (request == null || request.fieldIds() == null || request.fieldIds().isEmpty()) {
            throw new MalformedTemplateRequestException("Name at least one field to keep.");
        }
        requireKept(request.decision() == null ? "KEPT" : request.decision());
        fillSpotReviewService.keep(workspaceId, userId, templateId, List.copyOf(request.fieldIds()));
    }

    private static void requireKept(String decision) {
        if (!"KEPT".equals(decision)) {
            throw new MalformedTemplateRequestException("The only review a spot can have is \"KEPT\".");
        }
    }

    private TemplateVersionResponse versionResponse(long workspaceId, long userId, TemplateVersion version) {
        List<String> kept = fillSpotReviewService.keptFieldIds(workspaceId, userId, version.templateId()).stream().sorted().toList();
        return TemplateVersionResponse.from(version, kept);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    /**
     * {@code preparationNotices} may be left out: what the person was told
     * when the file was made ready to fill (the upload step's notices, as it
     * gave them), kept with the template so they can be shown again.
     */
    record CreateTemplateRequest(String displayName, long sourceArtifactId, List<PreparationNoticeRequest> preparationNotices) {
    }

    record PreparationNoticeRequest(String code, Integer count, String detail) {

        /** A list may hold a JSON null where a notice should be; that is a malformed request, not a server error. */
        static PreparationNotice toDomainOrRefuse(PreparationNoticeRequest request) {
            if (request == null) {
                throw new MalformedTemplateRequestException("A note about the template's file is missing.");
            }
            return request.toDomain();
        }

        PreparationNotice toDomain() {
            if (code == null || code.isBlank()) {
                throw new MalformedTemplateRequestException("A note about the template's file needs a code.");
            }
            if (count == null || count < 0) {
                throw new MalformedTemplateRequestException("A note about the template's file needs a count of zero or more.");
            }
            return new PreparationNotice(code, count, detail);
        }
    }

    /** {@code allowNoPlaces} lets a draft with no fields activate, for a form opened with no places found; left out, it is false. */
    record ActivateVersionRequest(int expectedVersionNumber, Boolean allowNoPlaces) {
    }

    record FieldReviewRequest(String decision) {
    }

    /** {@code decision} may be left out; the only one there is, is {@code KEPT}. */
    record FieldReviewsRequest(List<String> fieldIds, String decision) {
    }

    record ReplaceBindingsRequest(int expectedVersionNumber, List<FieldDefinitionRequest> fields) {
    }

    enum BindingKind {
        CONTENT_CONTROL_TAG,
        STRUCTURAL_NODE,
        ACROFORM_FIELD,
        PAGE_BOX
    }

    /**
     * Only the part {@code kind} chooses is required and the rest is ignored: {@code tag} for CONTENT_CONTROL_TAG,
     * {@code part}/{@code nodeId} for STRUCTURAL_NODE, {@code acroFormField} (a PDF form field's full name) for ACROFORM_FIELD,
     * {@code pageBox} for PAGE_BOX. The upload step gives each spot it finds its binding in this same shape, so a spot can be
     * sent back as a field as it is.
     */
    public record BindingRequest(
            BindingKind kind, String tag, DocumentPartKind part, String nodeId, String acroFormField, PageBoxBody pageBox) {

        public static BindingRequest from(FieldBindingTarget target) {
            return switch (target) {
                case FieldBindingTarget.ContentControlTag(String tag) ->
                        new BindingRequest(BindingKind.CONTENT_CONTROL_TAG, tag, null, null, null, null);
                case FieldBindingTarget.StructuralNode(var part, String nodeId) ->
                        new BindingRequest(BindingKind.STRUCTURAL_NODE, null, part, nodeId, null, null);
                case FieldBindingTarget.AcroFormField(String name) ->
                        new BindingRequest(BindingKind.ACROFORM_FIELD, null, null, null, name, null);
                case FieldBindingTarget.PageBox box -> new BindingRequest(BindingKind.PAGE_BOX, null, null, null, null, PageBoxBody.from(box));
            };
        }

        FieldBindingTarget toDomain() {
            if (kind == null) {
                throw new MalformedTemplateRequestException("A field binding requires a kind.");
            }
            return switch (kind) {
                case CONTENT_CONTROL_TAG -> {
                    if (tag == null || tag.isBlank()) {
                        throw new MalformedTemplateRequestException("A CONTENT_CONTROL_TAG binding requires a non-blank tag.");
                    }
                    yield new FieldBindingTarget.ContentControlTag(tag);
                }
                case STRUCTURAL_NODE -> {
                    if (part == null || nodeId == null || nodeId.isBlank()) {
                        throw new MalformedTemplateRequestException("A STRUCTURAL_NODE binding requires both part and nodeId.");
                    }
                    yield new FieldBindingTarget.StructuralNode(part, nodeId);
                }
                case ACROFORM_FIELD -> {
                    if (acroFormField == null || acroFormField.isEmpty()) {
                        throw new MalformedTemplateRequestException("An ACROFORM_FIELD binding requires the form field's full name.");
                    }
                    yield new FieldBindingTarget.AcroFormField(acroFormField);
                }
                case PAGE_BOX -> {
                    if (pageBox == null) {
                        throw new MalformedTemplateRequestException("A PAGE_BOX binding requires pageBox.");
                    }
                    yield pageBox.toDomain();
                }
            };
        }
    }

    /**
     * A box on a PDF page: points, the page as stored (before it is turned), origin at the top-left of the page's visible area,
     * Y down. {@code style} defaults to an ordinary 11 pt sans-serif, {@code multiline} to false and {@code overflow} to
     * SHRINK_TO_FIT (make the text smaller, down to 6 pt, when it does not fit; BLOCK stops export instead).
     */
    record PageBoxBody(
            Integer page, Double x, Double y, Double width, Double height, TextStyleBody style, Boolean multiline,
            PdfOverflowPolicy overflow) {

        static PageBoxBody from(FieldBindingTarget.PageBox box) {
            return new PageBoxBody(
                    box.page(), box.x(), box.y(), box.width(), box.height(), TextStyleBody.from(box.style()), box.multiline(),
                    box.overflow());
        }

        FieldBindingTarget.PageBox toDomain() {
            if (page == null || x == null || y == null || width == null || height == null) {
                throw new MalformedTemplateRequestException("A PAGE_BOX binding requires page, x, y, width and height.");
            }
            if (page < 1 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)) {
                throw new MalformedTemplateRequestException("A PAGE_BOX binding needs a page number from 1 and a box in points.");
            }
            PdfTextStyle textStyle = style == null ? PdfTextStyle.DEFAULT : style.toDomain();
            return new FieldBindingTarget.PageBox(
                    page, x, y, width, height, textStyle, Boolean.TRUE.equals(multiline),
                    overflow == null ? PdfOverflowPolicy.SHRINK_TO_FIT : overflow);
        }
    }

    /** How text in a box looks: one of three font families, bold or not, and its size in points (4 to 72). */
    record TextStyleBody(PdfFontFamily font, Boolean bold, Double sizePt) {

        static final double MIN_SIZE_PT = 4;
        static final double MAX_SIZE_PT = 72;

        static TextStyleBody from(PdfTextStyle style) {
            return new TextStyleBody(style.family(), style.bold(), style.sizePt());
        }

        PdfTextStyle toDomain() {
            double size = sizePt == null ? PdfTextStyle.DEFAULT.sizePt() : sizePt;
            if (!(size >= MIN_SIZE_PT && size <= MAX_SIZE_PT)) {
                throw new MalformedTemplateRequestException("A box's text size must be from 4 to 72 points.");
            }
            return new PdfTextStyle(font == null ? PdfTextStyle.DEFAULT.family() : font, Boolean.TRUE.equals(bold), size);
        }
    }

    /**
     * {@code label}, {@code origin}, {@code docxControl} and {@code blankText} are optional; a field that leaves them out is
     * one that came with the form and is named after its ID. A label is stored as {@link FieldIds#normalizeLabel} leaves it.
     */
    record FieldDefinitionRequest(
            String fieldId,
            FieldType type,
            FieldCardinality cardinality,
            FieldRequiredness requiredness,
            BindingRequest binding,
            String label,
            SpotOrigin origin,
            DocxControlOrigin docxControl,
            String blankText) {
        FieldDefinition toDomain() {
            if (fieldId == null || fieldId.isBlank()) {
                throw new MalformedTemplateRequestException("A field definition requires a non-blank fieldId.");
            }
            if (type == null || cardinality == null || requiredness == null || binding == null) {
                throw new MalformedTemplateRequestException(
                        "Field \"" + fieldId + "\" requires type, cardinality, requiredness, and binding.");
            }
            String storedLabel = FieldIds.normalizeLabel(label);
            if (label != null && storedLabel == null) {
                throw new MalformedTemplateRequestException(
                        "The name of field \"" + fieldId + "\" must be one line of 1 to " + FieldIds.MAX_LABEL_LENGTH
                                + " characters, with at least one letter or number.");
            }
            if (blankText != null && !FieldIds.isValidBlankText(blankText)) {
                throw new MalformedTemplateRequestException(
                        "The blank of field \"" + fieldId + "\" must be one line of 1 to " + FieldIds.MAX_BLANK_TEXT_LENGTH
                                + " characters, with no tabs or other control characters.");
            }
            return new FieldDefinition(
                    fieldId, type, cardinality, requiredness, binding.toDomain(), storedLabel, origin, docxControl, blankText);
        }
    }

    /**
     * {@code label}, {@code origin}, {@code docxControl} and {@code blankText} are null exactly when the field has none stored;
     * {@code acroFormField} and {@code pageBox} are set only for their own binding kind, like {@code tag}, {@code part} and
     * {@code nodeId}.
     */
    public record FieldDefinitionResponse(
            String fieldId,
            String type,
            String cardinality,
            String requiredness,
            String bindingKind,
            String tag,
            String part,
            String nodeId,
            String label,
            String origin,
            String docxControl,
            String blankText,
            String acroFormField,
            PageBoxBody pageBox) {
        public static FieldDefinitionResponse from(FieldDefinition field) {
            String origin = field.origin() == null ? null : field.origin().name();
            String docxControl = field.docxControl() == null ? null : field.docxControl().name();
            BindingRequest binding = BindingRequest.from(field.binding());
            return new FieldDefinitionResponse(
                    field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                    binding.kind().name(), binding.tag(), binding.part() == null ? null : binding.part().name(), binding.nodeId(),
                    field.label(), origin, docxControl, field.blankText(), binding.acroFormField(), binding.pageBox());
        }
    }

    record TemplateResponse(
            long id, String displayName, String status, Long currentActiveVersionId, OffsetDateTime createdAt, OffsetDateTime trashedAt) {
        static TemplateResponse from(Template template) {
            return new TemplateResponse(
                    template.id(), template.displayName(), template.status().name(), template.currentActiveVersionId(), template.createdAt(),
                    template.trashedAt());
        }
    }

    /**
     * {@code extractionVersionId} is set for a DOCX version and {@code pdfFormExtractionId} for a PDF one, never both;
     * {@code derivedFromVersionId} is the version this one was made from by correcting a document's fill spots, or null.
     * {@code preparationNotices} is what the person was told when the template's file was made ready to fill, kept with the
     * version and carried on to every version made from it; null for a version made before notices were kept.
     */
    public record TemplateVersionResponse(
            long id,
            long templateId,
            int versionNumber,
            long sourceArtifactId,
            String kind,
            Long extractionVersionId,
            Long pdfFormExtractionId,
            String status,
            List<FieldDefinitionResponse> fields,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt,
            List<String> acceptedFieldIds,
            Long derivedFromVersionId,
            List<PreparationNoticeResponse> preparationNotices) {
        /** {@code acceptedFieldIds} is the template's kept spots, which the page no longer marks as found by Brownie. */
        public static TemplateVersionResponse from(TemplateVersion version, List<String> acceptedFieldIds) {
            return new TemplateVersionResponse(
                    version.id(),
                    version.templateId(),
                    version.versionNumber(),
                    version.sourceArtifactId(),
                    version.kind().name(),
                    version.extractionVersionId(),
                    version.pdfFormExtractionId(),
                    version.status().name(),
                    version.fieldDefinitions().stream().map(FieldDefinitionResponse::from).toList(),
                    version.createdAt(),
                    version.activatedAt(),
                    acceptedFieldIds,
                    version.derivedFromVersionId(),
                    version.preparationNotices() == null
                            ? null
                            : version.preparationNotices().stream().map(PreparationNoticeResponse::from).toList());
        }
    }

    public record PreparationNoticeResponse(String code, int count, String detail) {

        static PreparationNoticeResponse from(PreparationNotice notice) {
            return new PreparationNoticeResponse(notice.code(), notice.count(), notice.detail());
        }
    }

    record TemplateDraftResponse(TemplateResponse template, TemplateVersionResponse draftVersion) {
    }

    /** A short, human-scannable stand-in for a node's own content -- never the full text of a large paragraph or table, since this response can otherwise easily dwarf the graph it describes. */
    private static final int TEXT_PREVIEW_MAX_LENGTH = 80;

    record StructuralNodeResponse(
            String nodeId,
            String kind,
            String textPreview,
            String contentControlTag,
            String imageRelationshipId,
            List<StructuralNodeResponse> children) {
        static StructuralNodeResponse from(StructuralNode node) {
            String preview = node.text() == null
                    ? null
                    : node.text().length() > TEXT_PREVIEW_MAX_LENGTH
                            ? node.text().substring(0, TEXT_PREVIEW_MAX_LENGTH) + "…"
                            : node.text();
            return new StructuralNodeResponse(
                    node.nodeId(),
                    node.kind().name(),
                    preview,
                    node.contentControlTag(),
                    node.imageRelationshipId(),
                    node.children().stream().map(StructuralNodeResponse::from).toList());
        }
    }

    record DocumentPartResponse(String partName, String kind, StructuralNodeResponse root) {
        static DocumentPartResponse from(DocumentPart part) {
            return new DocumentPartResponse(part.partName(), part.kind().name(), StructuralNodeResponse.from(part.root()));
        }
    }

    record DraftStructureResponse(String parserVersion, List<DocumentPartResponse> parts) {
        static DraftStructureResponse from(DocxStructuralGraph graph) {
            return new DraftStructureResponse(graph.parserVersion(), graph.parts().stream().map(DocumentPartResponse::from).toList());
        }
    }

    /** Every candidate this codebase's binding kinds can express is a {@code ContentControlTag} -- see {@code FieldBindingCandidateProposer}'s own javadoc for why a candidate is never proposed as a raw {@code StructuralNode}. */
    record CandidateFieldBindingResponse(String fieldId, String type, String cardinality, String contentControlTag) {
        static java.util.Optional<CandidateFieldBindingResponse> from(CandidateFieldBinding candidate) {
            if (!(candidate.binding() instanceof FieldBindingTarget.ContentControlTag(String tag))) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(
                    new CandidateFieldBindingResponse(candidate.fieldId(), candidate.type().name(), candidate.cardinality().name(), tag));
        }
    }

    /** A candidate bound any other way than by a tag has no place in this response's shape, so it is left out rather than sent half-described. */
    record CandidateBindingReportResponse(
            List<CandidateFieldBindingResponse> candidates, List<String> ambiguousContentControlTags, int untaggedContentControlCount) {
        static CandidateBindingReportResponse from(CandidateBindingReport report) {
            return new CandidateBindingReportResponse(
                    report.candidates().stream().map(CandidateFieldBindingResponse::from).flatMap(java.util.Optional::stream).toList(),
                    report.ambiguousContentControlTags(),
                    report.untaggedContentControlCount());
        }
    }
}
