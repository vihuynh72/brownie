package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.template.MalformedTemplateRequestException;
import io.github.vihuynh72.brownie.core.template.PdfBoxSuggestion;
import io.github.vihuynh72.brownie.core.template.TemplateLayoutService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a box on a PDF template's page would go for a person who pointed
 * at a place, or chose a line (the keyboard way): a box beside the label
 * there, one line tall, in the style of the words beside it, and the label
 * those words suggest. Worked out from the version's own form reading,
 * with no model call; the same request always gets the same answer, and
 * nothing is kept. A POST only because the place is a body, and counted as
 * a read.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/templates/{templateId}/versions/{versionId}/box-suggestion")
class PdfBoxSuggestionController {

    private final TemplateLayoutService templateLayoutService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    PdfBoxSuggestionController(
            TemplateLayoutService templateLayoutService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.templateLayoutService = templateLayoutService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    BoxSuggestionResponse suggest(
            @PathVariable long workspaceId,
            @PathVariable long templateId,
            @PathVariable long versionId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody BoxSuggestionRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        if (request == null || request.pageNumber() == null) {
            throw new MalformedTemplateRequestException("A box suggestion needs a pageNumber.");
        }
        if ((request.point() == null) == (request.lineIndex() == null)) {
            throw new MalformedTemplateRequestException("A box suggestion needs either a point or a lineIndex, not both.");
        }
        PdfPoint point = null;
        if (request.point() != null) {
            if (request.point().x() == null || request.point().y() == null) {
                throw new MalformedTemplateRequestException("A point on a page needs x and y in points.");
            }
            point = new PdfPoint(request.point().x(), request.point().y());
        }
        PdfBoxSuggestion suggestion = templateLayoutService.suggestBox(
                workspaceId, userId, templateId, versionId, request.pageNumber(), point, request.lineIndex());
        return BoxSuggestionResponse.from(suggestion);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    /** A point in points on the page as stored, origin at the top-left of its visible area, Y down; or a line's index on the page. */
    record BoxSuggestionRequest(Integer pageNumber, PointBody point, Integer lineIndex) {
    }

    record PointBody(Double x, Double y) {
    }

    record BoxSuggestionResponse(
            TemplateLayoutController.PdfBoxResponse box, TemplateController.TextStyleBody style, String labelGuess) {

        static BoxSuggestionResponse from(PdfBoxSuggestion suggestion) {
            return new BoxSuggestionResponse(
                    TemplateLayoutController.PdfBoxResponse.from(suggestion.box()),
                    TemplateController.TextStyleBody.from(suggestion.style()),
                    suggestion.labelGuess());
        }
    }
}
