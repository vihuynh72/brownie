package io.github.vihuynh72.brownie.api.prepare;

import io.github.vihuynh72.brownie.api.identity.AuthenticatedIdentityMissingException;
import io.github.vihuynh72.brownie.api.template.TemplateController;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.prepare.FillableForm;
import io.github.vihuynh72.brownie.core.prepare.FillableFormNotFoundException;
import io.github.vihuynh72.brownie.core.prepare.FillableFormService;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Makes an uploaded form ready to fill: the clean working copy a template
 * is built on, with the places to fill found and named. The first request
 * makes it (201); asking again answers with the same copy (200), and
 * {@code GET} reads what was made without making anything. Everything runs
 * inside the request, so a converted or large form can take a while; it
 * counts as rendering work for the rate limit, and waits its turn for the
 * same sandbox slots as rendering does.
 *
 * <p>A PDF is filled as it is, so no copy is made of one: the template is
 * built on the upload itself, and the first request is the one that read
 * it as a form. Its places are found again each time it is asked for, so
 * {@code GET} knows only Word copies.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/artifacts/{artifactId}/fillable-form")
class FillableFormController {

    private final FillableFormService fillableFormService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    FillableFormController(
            FillableFormService fillableFormService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.fillableFormService = fillableFormService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    ResponseEntity<FillableFormResponse> prepare(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("artifactId") long artifactId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = authorize(workspaceId, principal);
        FillableForm form = fillableFormService.prepare(workspaceId, userId, artifactId);
        return ResponseEntity.status(form.created() ? HttpStatus.CREATED : HttpStatus.OK).body(FillableFormResponse.from(form));
    }

    @GetMapping
    FillableFormResponse find(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("artifactId") long artifactId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = authorize(workspaceId, principal);
        return FillableFormResponse.from(fillableFormService.find(workspaceId, userId, artifactId)
                .orElseThrow(() -> new FillableFormNotFoundException(artifactId)));
    }

    /** Making a form ready touches both an upload and the template it becomes, so both capabilities are needed. */
    private long authorize(long workspaceId, OidcUser principal) {
        long userId = userIdentityRepository
                .findByIssuerAndSubject(principal.getIssuer().toString(), principal.getSubject())
                .orElseThrow(AuthenticatedIdentityMissingException::new)
                .id();
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return userId;
    }

    record FillableFormResponse(
            String kind,
            long sourceArtifactId,
            long templateSourceArtifactId,
            String sourceFormat,
            boolean converted,
            ExtractionResponse extraction,
            List<SpotResponse> spots,
            List<NoticeResponse> notices,
            String spotNaming,
            String rulesOnlyReason) {

        static FillableFormResponse from(FillableForm form) {
            return new FillableFormResponse(
                    form.kind().name(),
                    form.sourceArtifactId(),
                    form.templateSourceArtifactId(),
                    form.sourceFormat().name(),
                    form.converted(),
                    ExtractionResponse.from(form.extraction()),
                    form.spots().stream().map(SpotResponse::from).toList(),
                    form.notices().stream().map(NoticeResponse::from).toList(),
                    form.spotNaming().name(),
                    form.rulesOnlyReason());
        }
    }

    record ExtractionResponse(long id, String status, String parserVersion, List<KeptFeatureResponse> keptAsIs) {

        static ExtractionResponse from(FillableForm.Extraction extraction) {
            return new ExtractionResponse(extraction.id(), extraction.status(), extraction.parserVersion(),
                    extraction.keptAsIs().stream().map(kept -> new KeptFeatureResponse(kept.feature(), kept.count())).toList());
        }
    }

    record KeptFeatureResponse(String feature, int count) {
    }

    /**
     * One spot, ready to send back as a template field, its binding in the
     * template API's own shape: a Word spot is bound to its control's tag, a
     * PDF spot to one of the form's own fields or to a box on a page.
     * {@code label} is null for a control the form had already named with a
     * usable id (its name is worked out from the id); {@code blankText} is
     * the form's own blank the spot replaced, or null; {@code docxControl}
     * is null for a PDF.
     */
    record SpotResponse(
            String fieldId,
            String label,
            String type,
            String cardinality,
            String requiredness,
            TemplateController.BindingRequest binding,
            String origin,
            String docxControl,
            String blankText,
            String namedBy,
            boolean requiredHint,
            String suggestedType,
            String foundAs) {

        static SpotResponse from(FillableForm.Spot spot) {
            FieldDefinition field = spot.field();
            return new SpotResponse(
                    field.fieldId(),
                    field.label(),
                    field.type().name(),
                    field.cardinality().name(),
                    field.requiredness().name(),
                    TemplateController.BindingRequest.from(field.binding()),
                    field.effectiveOrigin().name(),
                    field.docxControl() == null ? null : field.docxControl().name(),
                    field.blankText(),
                    spot.namedBy().name(),
                    spot.requiredHint(),
                    spot.suggestedType(),
                    spot.kind());
        }
    }

    record NoticeResponse(String code, int count, String detail) {

        static NoticeResponse from(PreparationNotice notice) {
            return new NoticeResponse(notice.code(), notice.count(), notice.detail());
        }
    }
}
