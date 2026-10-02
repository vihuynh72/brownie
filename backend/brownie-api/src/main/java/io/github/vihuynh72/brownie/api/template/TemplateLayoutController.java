package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.template.PdfTemplateLayout;
import io.github.vihuynh72.brownie.core.template.TemplateLayout;
import io.github.vihuynh72.brownie.core.template.TemplateLayoutService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * One template version's document as a page: its text and tables with the
 * style they are set in, and a fill spot wherever a field's value goes, so
 * a person can fill a document where its values will actually appear. See
 * {@code TemplateLayoutProjector} for how fill spots are placed. Reads the
 * template's own file on every request and changes nothing.
 *
 * <p>{@code kind} says which page it is. A Word template's is its blocks
 * ({@code parts}); a PDF template's is its pages and the places on them
 * ({@code pdf}), with no blocks, since the viewer draws the PDF itself.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/templates/{templateId}/versions/{versionId}/layout")
class TemplateLayoutController {

    private final TemplateLayoutService templateLayoutService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    TemplateLayoutController(
            TemplateLayoutService templateLayoutService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.templateLayoutService = templateLayoutService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping
    TemplateLayoutResponse layout(
            @PathVariable long workspaceId,
            @PathVariable long templateId,
            @PathVariable long versionId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return templateLayoutService.pdfLayout(workspaceId, userId, templateId, versionId)
                .map(TemplateLayoutResponse::fromPdf)
                .orElseGet(() -> TemplateLayoutResponse.from(templateLayoutService.layout(workspaceId, userId, templateId, versionId)));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    record TemplateLayoutResponse(
            long templateId,
            long versionId,
            String kind,
            String parserVersion,
            List<TemplateLayoutPartResponse> parts,
            List<String> unplacedFieldIds,
            PdfLayoutResponse pdf) {

        static TemplateLayoutResponse from(TemplateLayout layout) {
            return new TemplateLayoutResponse(
                    layout.templateId(),
                    layout.versionId(),
                    "DOCX",
                    layout.parserVersion(),
                    layout.parts().stream().map(TemplateLayoutPartResponse::from).toList(),
                    layout.unplacedFieldIds(),
                    null);
        }

        static TemplateLayoutResponse fromPdf(PdfTemplateLayout layout) {
            return new TemplateLayoutResponse(
                    layout.templateId(), layout.versionId(), "PDF", layout.parserVersion(), List.of(), List.of(), PdfLayoutResponse.from(layout));
        }
    }

    record PdfLayoutResponse(long sourceArtifactId, List<PdfPageResponse> pages, List<PdfSpotResponse> spots) {

        static PdfLayoutResponse from(PdfTemplateLayout layout) {
            return new PdfLayoutResponse(
                    layout.sourceArtifactId(),
                    layout.pages().stream().map(PdfPageResponse::from).toList(),
                    layout.spots().stream().map(PdfSpotResponse::from).toList());
        }
    }

    record PdfPageResponse(int pageNumber, double width, double height, int rotation, boolean hasText, List<PdfLineResponse> lines) {

        static PdfPageResponse from(PdfTemplateLayout.Page page) {
            return new PdfPageResponse(page.pageNumber(), page.width(), page.height(), page.rotation(), page.hasText(),
                    page.lines().stream().map(PdfLineResponse::from).toList());
        }
    }

    record PdfLineResponse(int index, String text, double x, double y, double w, double h) {

        static PdfLineResponse from(PdfTemplateLayout.Line line) {
            return new PdfLineResponse(line.index(), line.text(), line.box().x(), line.box().y(), line.box().width(), line.box().height());
        }
    }

    /** {@code style} is null for one of the form's own fields, whose look the form sets. */
    record PdfSpotResponse(
            String fieldId,
            String label,
            String origin,
            int pageNumber,
            PdfBoxResponse box,
            TemplateController.TextStyleBody style,
            boolean multiline,
            String overflow,
            String bindingKind) {

        static PdfSpotResponse from(PdfTemplateLayout.Spot spot) {
            return new PdfSpotResponse(spot.fieldId(), spot.label(), spot.origin().name(), spot.pageNumber(), PdfBoxResponse.from(spot.box()),
                    spot.style() == null ? null : TemplateController.TextStyleBody.from(spot.style()), spot.multiline(),
                    spot.overflow().name(), spot.bindingKind());
        }
    }

    record PdfBoxResponse(double x, double y, double width, double height) {

        static PdfBoxResponse from(PdfRect box) {
            return new PdfBoxResponse(box.x(), box.y(), box.width(), box.height());
        }
    }

    record TemplateLayoutPartResponse(String kind, List<TemplateLayoutBlockResponse> blocks) {

        static TemplateLayoutPartResponse from(TemplateLayout.Part part) {
            return new TemplateLayoutPartResponse(
                    part.kind().name(), part.blocks().stream().map(TemplateLayoutBlockResponse::from).toList());
        }
    }

    /**
     * {@code alignment}, {@code listLevel}, {@code inlines}, {@code nodeId}
     * and {@code anchorTextHash} belong to a PARAGRAPH and {@code rows} to a
     * TABLE; the other kind leaves them null, and a table is never anchorable.
     */
    record TemplateLayoutBlockResponse(
            String kind,
            String alignment,
            Integer listLevel,
            boolean repeating,
            List<TemplateLayoutInlineResponse> inlines,
            List<TemplateLayoutRowResponse> rows,
            String nodeId,
            boolean anchorable,
            String anchorTextHash) {

        static TemplateLayoutBlockResponse from(TemplateLayout.Block block) {
            return switch (block) {
                case TemplateLayout.Paragraph paragraph -> new TemplateLayoutBlockResponse(
                        "PARAGRAPH",
                        paragraph.alignment() == null ? null : paragraph.alignment().name(),
                        paragraph.listLevel(),
                        paragraph.repeating(),
                        paragraph.inlines().stream().map(TemplateLayoutInlineResponse::from).toList(),
                        null,
                        paragraph.nodeId(),
                        paragraph.anchorable(),
                        paragraph.anchorTextHash());
                case TemplateLayout.Table table -> new TemplateLayoutBlockResponse(
                        "TABLE", null, null, false, null, table.rows().stream().map(TemplateLayoutRowResponse::from).toList(),
                        null, false, null);
            };
        }
    }

    record TemplateLayoutRowResponse(boolean repeating, List<TemplateLayoutCellResponse> cells) {

        static TemplateLayoutRowResponse from(TemplateLayout.Row row) {
            return new TemplateLayoutRowResponse(row.repeating(), row.cells().stream().map(TemplateLayoutCellResponse::from).toList());
        }
    }

    record TemplateLayoutCellResponse(List<TemplateLayoutBlockResponse> blocks) {

        static TemplateLayoutCellResponse from(TemplateLayout.Cell cell) {
            return new TemplateLayoutCellResponse(cell.blocks().stream().map(TemplateLayoutBlockResponse::from).toList());
        }
    }

    /**
     * {@code text}, {@code anchorStart} and {@code controlNodeId} belong to
     * TEXT; {@code fieldId}, {@code placeholder}, {@code nodeId}, {@code
     * origin} and {@code label} to FILL_SPOT.
     */
    record TemplateLayoutInlineResponse(
            String kind,
            String text,
            String fieldId,
            String placeholder,
            TemplateLayoutStyleResponse style,
            Integer anchorStart,
            String controlNodeId,
            String nodeId,
            String origin,
            String label) {

        static TemplateLayoutInlineResponse from(TemplateLayout.Inline inline) {
            return switch (inline) {
                case TemplateLayout.Text text -> new TemplateLayoutInlineResponse(
                        "TEXT", text.text(), null, null, TemplateLayoutStyleResponse.from(text.style()), text.anchorStart(),
                        text.controlNodeId(), null, null, null);
                case TemplateLayout.FillSpot spot -> new TemplateLayoutInlineResponse(
                        "FILL_SPOT", null, spot.fieldId(), spot.placeholder(), TemplateLayoutStyleResponse.from(spot.style()), null, null,
                        spot.nodeId(), spot.origin().name(), spot.label());
                case TemplateLayout.Image ignored -> new TemplateLayoutInlineResponse("IMAGE", null, null, null, null, null, null, null, null, null);
            };
        }
    }

    record TemplateLayoutStyleResponse(
            Boolean bold, Boolean italic, Boolean underline, String fontFamily, Integer fontSizeHalfPoints, String colorHex) {

        static TemplateLayoutStyleResponse from(TemplateLayout.Style style) {
            return style == null
                    ? null
                    : new TemplateLayoutStyleResponse(
                            style.bold(), style.italic(), style.underline(), style.fontFamily(), style.fontSizeHalfPoints(), style.colorHex());
        }
    }
}
