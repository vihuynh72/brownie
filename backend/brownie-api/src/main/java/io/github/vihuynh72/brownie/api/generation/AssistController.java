package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.revision.DocxAnchorRequest;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.PdfPoint;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.prepare.DocxAnchor;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The Assist composer's two calls: interpret a typed request into one
 * bounded command with its scope, then execute exactly that. A change or a
 * rewrite comes back as a patch proposal for the ordinary accept route, an
 * explanation is text, and a draft request is carried out by the workspace
 * through the existing generation route. Adding, renaming or taking away a
 * fill spot is applied at once, and the answer says what Undo restores.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents/{documentId}/assist")
class AssistController {

    private final AssistService assistService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    AssistController(
            AssistService assistService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.assistService = assistService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    /** No side effects: what the request would do and to which field or finding, or what Brownie can do instead. */
    @PostMapping("/interpret")
    AssistInterpretationResponse interpret(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody AssistTextRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        return AssistInterpretationResponse.from(assistService.interpret(
                workspaceId, userId, documentId, request.text(), request.pageAnchor() == null ? null : request.pageAnchor().toDomain()));
    }

    /** Executes the interpreted command against the revision the person was looking at (412 if it moved on). */
    @PostMapping("/execute")
    AssistExecutionResponse execute(
            @PathVariable long workspaceId,
            @PathVariable long documentId,
            @RequestBody AssistExecuteRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
        if (request.expectedRevisionId() <= 0) {
            throw new AssistRequestValidationException("expectedRevisionId must be positive.");
        }
        return AssistExecutionResponse.from(assistService.execute(
                workspaceId, userId, documentId, request.text(), request.expectedRevisionId(),
                request.pageAnchor() == null ? null : request.pageAnchor().toDomain()));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new AuthenticatedIdentityMissingException())
                .id();
    }

    /** {@code pageAnchor} is the place selected on the page, if any: what "here" and "this line" mean, or the line a person chose. */
    record AssistTextRequest(String text, PageAnchorRequest pageAnchor) {
    }

    record AssistExecuteRequest(String text, long expectedRevisionId, PageAnchorRequest pageAnchor) {
    }

    /**
     * A place selected on the page. Without {@code kind}, or with WORD, it
     * is a place in a Word form's text, in {@link DocxAnchorRequest}'s
     * shape. With {@code kind} PDF it is on a PDF form's page {@code
     * pageNumber}: either a {@code point} (points, the page as stored,
     * origin at its top-left, Y down) or the page's line {@code lineIndex}.
     */
    record PageAnchorRequest(
            String kind,
            String part,
            String paragraphNodeId,
            String placement,
            Integer start,
            Integer end,
            String anchorTextHash,
            String parserVersion,
            String controlNodeId,
            Integer pageNumber,
            PointBody point,
            Integer lineIndex) {

        AssistService.PageAnchor toDomain() {
            if (kind == null || kind.equals("WORD")) {
                return new AssistService.PageAnchor.Word(new DocxAnchorRequest(
                        part, paragraphNodeId, placement, start, end, anchorTextHash, parserVersion, controlNodeId).toDomain());
            }
            if (!kind.equals("PDF")) {
                throw new AssistRequestValidationException("pageAnchor.kind must be WORD or PDF.");
            }
            if (pageNumber == null || pageNumber < 1) {
                throw new AssistRequestValidationException("A place on a PDF page needs a pageNumber from 1.");
            }
            if ((point == null) == (lineIndex == null)) {
                throw new AssistRequestValidationException("A place on a PDF page is either a point or a lineIndex.");
            }
            if (point != null && (point.x() == null || point.y() == null || !Double.isFinite(point.x()) || !Double.isFinite(point.y()))) {
                throw new AssistRequestValidationException("A point on a PDF page needs x and y in points.");
            }
            if (lineIndex != null && lineIndex < 0) {
                throw new AssistRequestValidationException("A line on a PDF page is counted from 0.");
            }
            return new AssistService.PageAnchor.Pdf(pageNumber, point == null ? null : new PdfPoint(point.x(), point.y()), lineIndex);
        }
    }

    record PointBody(Double x, Double y) {
    }

    record PlaceChoiceResponse(String lineText, PageAnchorResponse anchor) {
        static PlaceChoiceResponse from(AssistService.PlaceChoice choice) {
            return new PlaceChoiceResponse(choice.lineText(), switch (choice.anchor()) {
                case AssistService.PageAnchor.Word(DocxAnchor anchor) -> DocxAnchorResponse.from(anchor);
                case AssistService.PageAnchor.Pdf pdf -> new PdfAnchorResponse("PDF", pdf.pageNumber(), pdf.lineIndex());
            });
        }
    }

    /** A chosen place, in the shape a page anchor is sent in, so it goes back as it came. */
    sealed interface PageAnchorResponse permits DocxAnchorResponse, PdfAnchorResponse {
    }

    record DocxAnchorResponse(
            String part, String paragraphNodeId, String placement, int start, int end, String anchorTextHash, String parserVersion,
            String controlNodeId) implements PageAnchorResponse {
        static DocxAnchorResponse from(DocxAnchor anchor) {
            return new DocxAnchorResponse(
                    anchor.part().name(), anchor.paragraphNodeId(), anchor.placement().name(), anchor.start(), anchor.end(),
                    anchor.anchorTextHash(), anchor.parserVersion(), anchor.controlNodeId());
        }
    }

    /** A line on a PDF page: always a line, never a point, since a choice is one of the lines the quoted words are on. */
    record PdfAnchorResponse(String kind, int pageNumber, Integer lineIndex) implements PageAnchorResponse {
    }

    record SpotChangeResponse(String fieldId, String label, String lineText, long previousRevisionId, long templateVersionId) {
        static SpotChangeResponse from(AssistService.SpotChange change) {
            return change == null
                    ? null
                    : new SpotChangeResponse(change.fieldId(), change.label(), change.lineText(), change.previousRevisionId(), change.templateVersionId());
        }
    }

    record AssistScopeResponse(String fieldId, String label, String currentValue, String findingMessage) {
        static AssistScopeResponse from(AssistService.Scope scope) {
            return scope == null ? null : new AssistScopeResponse(scope.fieldId(), scope.label(), scope.currentValue(), scope.findingMessage());
        }
    }

    record AssistInterpretationResponse(
            String kind,
            String summary,
            AssistScopeResponse scope,
            boolean executable,
            boolean usesModel,
            List<String> help,
            List<PlaceChoiceResponse> choices) {
        static AssistInterpretationResponse from(AssistService.Interpretation interpretation) {
            return new AssistInterpretationResponse(
                    interpretation.kind().name(),
                    interpretation.summary(),
                    AssistScopeResponse.from(interpretation.scope()),
                    interpretation.executable(),
                    interpretation.usesModel(),
                    interpretation.help(),
                    interpretation.choices().stream().map(PlaceChoiceResponse::from).toList());
        }
    }

    /** {@code spotChange} is set when the request added, renamed or took away a fill spot, which is applied at once. */
    record AssistExecutionResponse(
            String kind,
            String summary,
            GenerationController.PatchProposalResponse proposal,
            String explanation,
            List<String> help,
            SpotChangeResponse spotChange) {
        static AssistExecutionResponse from(AssistService.Execution execution) {
            return new AssistExecutionResponse(
                    execution.kind().name(),
                    execution.summary(),
                    execution.proposal() == null ? null : GenerationController.PatchProposalResponse.fromProposal(execution.proposal()),
                    execution.explanation(),
                    execution.help(),
                    SpotChangeResponse.from(execution.spotChange()));
        }
    }
}
