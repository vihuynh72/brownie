package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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
        return TemplateLayoutResponse.from(templateLayoutService.layout(workspaceId, userId, templateId, versionId));
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
            String parserVersion,
            List<TemplateLayoutPartResponse> parts,
            List<String> unplacedFieldIds) {

        static TemplateLayoutResponse from(TemplateLayout layout) {
            return new TemplateLayoutResponse(
                    layout.templateId(),
                    layout.versionId(),
                    layout.parserVersion(),
                    layout.parts().stream().map(TemplateLayoutPartResponse::from).toList(),
                    layout.unplacedFieldIds());
        }
    }

    record TemplateLayoutPartResponse(String kind, List<TemplateLayoutBlockResponse> blocks) {

        static TemplateLayoutPartResponse from(TemplateLayout.Part part) {
            return new TemplateLayoutPartResponse(
                    part.kind().name(), part.blocks().stream().map(TemplateLayoutBlockResponse::from).toList());
        }
    }

    /** {@code alignment}, {@code listLevel} and {@code inlines} belong to a PARAGRAPH and {@code rows} to a TABLE; the other kind leaves them null. */
    record TemplateLayoutBlockResponse(
            String kind,
            String alignment,
            Integer listLevel,
            boolean repeating,
            List<TemplateLayoutInlineResponse> inlines,
            List<TemplateLayoutRowResponse> rows) {

        static TemplateLayoutBlockResponse from(TemplateLayout.Block block) {
            return switch (block) {
                case TemplateLayout.Paragraph paragraph -> new TemplateLayoutBlockResponse(
                        "PARAGRAPH",
                        paragraph.alignment() == null ? null : paragraph.alignment().name(),
                        paragraph.listLevel(),
                        paragraph.repeating(),
                        paragraph.inlines().stream().map(TemplateLayoutInlineResponse::from).toList(),
                        null);
                case TemplateLayout.Table table -> new TemplateLayoutBlockResponse(
                        "TABLE", null, null, false, null, table.rows().stream().map(TemplateLayoutRowResponse::from).toList());
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

    record TemplateLayoutInlineResponse(
            String kind, String text, String fieldId, String placeholder, TemplateLayoutStyleResponse style) {

        static TemplateLayoutInlineResponse from(TemplateLayout.Inline inline) {
            return switch (inline) {
                case TemplateLayout.Text(String text, TemplateLayout.Style style) ->
                        new TemplateLayoutInlineResponse("TEXT", text, null, null, TemplateLayoutStyleResponse.from(style));
                case TemplateLayout.FillSpot(String fieldId, String placeholder, TemplateLayout.Style style) ->
                        new TemplateLayoutInlineResponse("FILL_SPOT", null, fieldId, placeholder, TemplateLayoutStyleResponse.from(style));
                case TemplateLayout.Image ignored -> new TemplateLayoutInlineResponse("IMAGE", null, null, null, null);
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
