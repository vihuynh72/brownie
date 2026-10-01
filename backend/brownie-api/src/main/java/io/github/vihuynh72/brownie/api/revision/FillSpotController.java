package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.api.template.TemplateController;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.FillSpotChange;
import io.github.vihuynh72.brownie.core.template.FillSpotReviewService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Corrections to an open document's fill spots, and moving a document to
 * another version of its form. Both answer with the document as it is now
 * and the revision the change appended, so the page can offer Undo through
 * the ordinary restore route. See {@link FillSpotService}.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}")
class FillSpotController {

    private final FillSpotService fillSpotService;
    private final FillSpotReviewService fillSpotReviewService;
    private final CanonicalRequestHasher canonicalRequestHasher;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    FillSpotController(
            FillSpotService fillSpotService,
            FillSpotReviewService fillSpotReviewService,
            CanonicalRequestHasher canonicalRequestHasher,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.fillSpotService = fillSpotService;
        this.fillSpotReviewService = fillSpotReviewService;
        this.canonicalRequestHasher = canonicalRequestHasher;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /**
     * Adds, renames or takes away fill spots, or on a PDF form moves a box
     * or changes how it shows its text, as one new version of the form the
     * document moves to. Refused with 412 unless {@code expectedRevisionId}
     * is current, and with 409 when the document is not on {@code
     * templateVersionId} or the form moved on meanwhile.
     */
    @PostMapping("/fill-spots")
    FillSpotsResponse changeFillSpots(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody FillSpotsRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        requireTemplateAccess(userId, workspaceId);
        FillSpotService.FillSpotResult result = fillSpotService.changeFillSpots(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new FillSpotsHashInput("document.fill-spots", workspaceId, documentId, request)),
                positive(documentId, "documentId"),
                positive(request.expectedRevisionId(), "expectedRevisionId"),
                positive(request.templateVersionId(), "templateVersionId"),
                request.toChanges());
        DocumentMutationResult mutation = result.mutation();
        return new FillSpotsResponse(
                DocumentController.DocumentResponse.from(mutation.document(), mutation.revision(), result.templateLatestVersionId()),
                DocumentController.DocumentRevisionResponse.from(mutation.revision()),
                versionResponse(workspaceId, userId, result.templateVersion()),
                result.previousRevisionId(),
                result.fieldIds(),
                result.otherDocumentsOnPreviousVersion());
    }

    /** Moves the document to another activated version of its form, keeping every value that version has a spot for. */
    @PostMapping("/template-version")
    TemplateVersionMoveResponse moveToTemplateVersion(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody TemplateVersionMoveRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        FillSpotService.VersionMoveResult result = fillSpotService.moveToTemplateVersion(
                workspaceId,
                userId,
                requireIdempotencyKey(idempotencyKey),
                canonicalRequestHasher.hash(new TemplateVersionMoveHashInput("document.move-template-version", workspaceId, documentId, request)),
                positive(documentId, "documentId"),
                positive(request.expectedRevisionId(), "expectedRevisionId"),
                positive(request.templateVersionId(), "templateVersionId"));
        DocumentMutationResult mutation = result.move().mutation();
        return new TemplateVersionMoveResponse(
                DocumentController.DocumentResponse.from(mutation.document(), mutation.revision(), result.templateLatestVersionId()),
                DocumentController.DocumentRevisionResponse.from(mutation.revision()),
                result.move().previousTemplateVersionId(),
                result.move().droppedFieldIds());
    }

    /** The new version as the template API shows it, with the template's kept spots, so the page marks found spots the same way. */
    private TemplateController.TemplateVersionResponse versionResponse(long workspaceId, long userId, TemplateVersion version) {
        List<String> kept = fillSpotReviewService.keptFieldIds(workspaceId, userId, version.templateId()).stream().sorted().toList();
        return TemplateController.TemplateVersionResponse.from(version, kept);
    }

    private void requireAccess(long userId, long workspaceId) {
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
    }

    /** A correction makes a new version of the form and moves the form to it, as changing a template does. */
    private void requireTemplateAccess(long userId, long workspaceId) {
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    private static long positive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new DocumentRequestValidationException(field + " must be positive.");
        }
        return value;
    }

    private static IdempotencyKey requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new DocumentRequestValidationException("Idempotency-Key must contain non-blank text up to 200 characters.");
        }
        return new IdempotencyKey(value);
    }

    private record FillSpotsHashInput(String operation, long workspaceId, long documentId, FillSpotsRequest request) {
    }

    private record TemplateVersionMoveHashInput(String operation, long workspaceId, long documentId, TemplateVersionMoveRequest request) {
    }

    /** Numbers are boxed so that leaving one out is answered as the malformed request it is. */
    record FillSpotsRequest(Long expectedRevisionId, Long templateVersionId, List<FillSpotChangeRequest> changes) {

        List<FillSpotChange> toChanges() {
            if (changes == null || changes.isEmpty()) {
                throw new DocumentRequestValidationException("changes must name at least one fill spot to add, change or take away.");
            }
            return changes.stream().map(change -> {
                if (change == null) {
                    throw new DocumentRequestValidationException("changes must not contain null.");
                }
                return change.toDomain();
            }).toList();
        }
    }

    /**
     * {@code kind} ADD takes {@code label}, {@code type} (TEXT or DATE,
     * TEXT when left out) and {@code anchor}; RENAME takes {@code fieldId}
     * and {@code label}; REMOVE takes {@code fieldId}. On a PDF form, ADD_BOX
     * takes {@code pageNumber}, {@code box}, {@code label} and {@code type},
     * and may take {@code style} (left out, the look of the words beside
     * the box), {@code multiline} (false) and {@code overflow}
     * (SHRINK_TO_FIT); MOVE_BOX takes {@code fieldId} and {@code box};
     * RESTYLE_BOX takes {@code fieldId} and at least one of {@code sizePt},
     * {@code overflow} and {@code multiline}.
     */
    record FillSpotChangeRequest(
            String kind,
            String fieldId,
            String label,
            String type,
            DocxAnchorRequest anchor,
            Integer pageNumber,
            BoxBody box,
            TextStyleBody style,
            Boolean multiline,
            String overflow,
            Double sizePt) {

        FillSpotChange toDomain() {
            if (kind == null) {
                throw new DocumentRequestValidationException(
                        "Every change needs a kind: ADD, RENAME, REMOVE, ADD_BOX, MOVE_BOX or RESTYLE_BOX.");
            }
            return switch (kind) {
                case "ADD" -> {
                    if (anchor == null) {
                        throw new DocumentRequestValidationException("An ADD change needs an anchor.");
                    }
                    yield new FillSpotChange.Add(anchor.toDomain(), requireText(label, "label"), fieldType(), false);
                }
                case "RENAME" -> new FillSpotChange.Rename(requireText(fieldId, "fieldId"), requireText(label, "label"));
                case "REMOVE" -> new FillSpotChange.Remove(requireText(fieldId, "fieldId"));
                case "ADD_BOX" -> {
                    if (pageNumber == null || pageNumber < 1) {
                        throw new DocumentRequestValidationException("An ADD_BOX change needs a pageNumber from 1.");
                    }
                    yield new FillSpotChange.AddBox(pageNumber, requireBox(), requireText(label, "label"), fieldType(),
                            style == null ? null : style.toDomain(), Boolean.TRUE.equals(multiline), overflowPolicy(), false);
                }
                case "MOVE_BOX" -> new FillSpotChange.MoveBox(requireText(fieldId, "fieldId"), requireBox());
                case "RESTYLE_BOX" -> {
                    if (sizePt == null && overflow == null && multiline == null) {
                        throw new DocumentRequestValidationException("A RESTYLE_BOX change needs sizePt, overflow or multiline.");
                    }
                    yield new FillSpotChange.RestyleBox(requireText(fieldId, "fieldId"), sizePt, overflowPolicy(), multiline);
                }
                default -> throw new DocumentRequestValidationException(
                        "kind must be ADD, RENAME, REMOVE, ADD_BOX, MOVE_BOX or RESTYLE_BOX.");
            };
        }

        private FieldType fieldType() {
            if (type == null) {
                return FieldType.TEXT;
            }
            try {
                return FieldType.valueOf(type);
            } catch (IllegalArgumentException e) {
                throw new DocumentRequestValidationException("type must be TEXT or DATE.");
            }
        }

        private PdfRect requireBox() {
            if (box == null) {
                throw new DocumentRequestValidationException("A box change needs a box: x, y, width and height in points.");
            }
            return box.toDomain();
        }

        private PdfOverflowPolicy overflowPolicy() {
            if (overflow == null) {
                return null;
            }
            try {
                return PdfOverflowPolicy.valueOf(overflow);
            } catch (IllegalArgumentException e) {
                throw new DocumentRequestValidationException("overflow must be SHRINK_TO_FIT or BLOCK.");
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 200) {
                throw new DocumentRequestValidationException(field + " must be 1 to 200 characters.");
            }
            return value;
        }
    }

    /** A box on a PDF page: points, the page as stored, origin at the top-left of its visible area, Y down. */
    record BoxBody(Double x, Double y, Double width, Double height) {

        PdfRect toDomain() {
            if (x == null || y == null || width == null || height == null
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)) {
                throw new DocumentRequestValidationException("A box needs x, y, width and height in points.");
            }
            return new PdfRect(x, y, width, height);
        }
    }

    /** How a new box's text looks: a font family (SANS when left out), bold or not, and a size in points (11 when left out). */
    record TextStyleBody(String font, Boolean bold, Double sizePt) {

        PdfTextStyle toDomain() {
            PdfFontFamily family;
            try {
                family = font == null ? PdfTextStyle.DEFAULT.family() : PdfFontFamily.valueOf(font);
            } catch (IllegalArgumentException e) {
                throw new DocumentRequestValidationException("style.font must be SANS, SERIF or MONO.");
            }
            double size = sizePt == null ? PdfTextStyle.DEFAULT.sizePt() : sizePt;
            if (!Double.isFinite(size) || size <= 0) {
                throw new DocumentRequestValidationException("style.sizePt must be a size in points.");
            }
            return new PdfTextStyle(family, Boolean.TRUE.equals(bold), size);
        }
    }

    record FillSpotsResponse(
            DocumentController.DocumentResponse document,
            DocumentController.DocumentRevisionResponse revision,
            TemplateController.TemplateVersionResponse templateVersion,
            long previousRevisionId,
            List<String> fieldIds,
            int otherDocumentsOnPreviousVersion) {
    }

    record TemplateVersionMoveRequest(Long expectedRevisionId, Long templateVersionId) {
    }

    record TemplateVersionMoveResponse(
            DocumentController.DocumentResponse document,
            DocumentController.DocumentRevisionResponse revision,
            long previousTemplateVersionId,
            List<String> droppedFieldIds) {
    }
}
