package io.github.vihuynh72.brownie.api.example;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.example.TemplateExample;
import io.github.vihuynh72.brownie.core.example.TemplateExampleService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Attaches a completed example to a template's own current draft version
 * and lists what has been attached -- the "examples" surface {@code
 * TemplateController}'s own javadoc names as deliberately not exposed
 * there. Kept as its own controller for the same reason rule decisions will
 * be: a template's draft/bind/activate lifecycle and its example-teaching
 * surface are two different concerns sharing one underlying draft version,
 * not one growing controller.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/templates/{templateId}/examples")
class ExampleController {

    private final TemplateExampleService templateExampleService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    ExampleController(
            TemplateExampleService templateExampleService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.templateExampleService = templateExampleService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TemplateExampleResponse attach(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody AttachExampleRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        TemplateExample example =
                templateExampleService.attachExample(workspaceId, userId, templateId, request.sourceArtifactId());
        return TemplateExampleResponse.from(example);
    }

    @GetMapping
    List<TemplateExampleResponse> list(
            @PathVariable("workspaceId") long workspaceId,
            @PathVariable("templateId") long templateId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return templateExampleService.findForDraft(workspaceId, userId, templateId).stream()
                .map(TemplateExampleResponse::from)
                .toList();
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

    record AttachExampleRequest(long sourceArtifactId) {
    }

    record TemplateExampleResponse(
            long id,
            long templateId,
            long templateVersionId,
            long sourceArtifactId,
            long extractionVersionId,
            String alignmentStatus,
            OffsetDateTime createdAt) {
        static TemplateExampleResponse from(TemplateExample example) {
            return new TemplateExampleResponse(
                    example.id(),
                    example.templateId(),
                    example.templateVersionId(),
                    example.sourceArtifactId(),
                    example.extractionVersionId(),
                    example.alignmentStatus().name(),
                    example.createdAt());
        }
    }
}
