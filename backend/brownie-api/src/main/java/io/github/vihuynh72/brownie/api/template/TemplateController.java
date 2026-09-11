package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.MalformedTemplateRequestException;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Creates a template draft against an already-extracted DOCX source,
 * replaces its field definitions and bindings, and activates an immutable
 * version. The examples and rule-decision endpoints are not exposed here.
 * Deliberately returns the draft version alongside the template on
 * creation, since a caller needs that version's own number for the very
 * next {@code PUT .../draft/bindings} call.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/templates")
class TemplateController {

    private final TemplateService templateService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    TemplateController(
            TemplateService templateService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.templateService = templateService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TemplateDraftResponse create(
            @PathVariable("workspaceId") long workspaceId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody CreateTemplateRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        Template template = templateService.createDraft(workspaceId, userId, request.displayName(), request.sourceArtifactId());
        TemplateVersion draft = templateService
                .findDraftVersion(workspaceId, userId, template.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Template " + template.id() + " has no draft version immediately after creating it."));
        return TemplateDraftResponse.from(template, draft);
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
        return TemplateVersionResponse.from(updated);
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
        TemplateVersion activated = templateService.activate(workspaceId, userId, templateId, request.expectedVersionNumber());
        return TemplateVersionResponse.from(activated);
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

    record CreateTemplateRequest(String displayName, long sourceArtifactId) {
    }

    record ActivateVersionRequest(int expectedVersionNumber) {
    }

    record ReplaceBindingsRequest(int expectedVersionNumber, List<FieldDefinitionRequest> fields) {
    }

    enum BindingKind {
        CONTENT_CONTROL_TAG,
        STRUCTURAL_NODE
    }

    /** Only {@code tag} (for CONTENT_CONTROL_TAG) or {@code part}/{@code nodeId} (for STRUCTURAL_NODE) is required, chosen by {@code kind}; the other is ignored. */
    record BindingRequest(BindingKind kind, String tag, DocumentPartKind part, String nodeId) {
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
            };
        }
    }

    record FieldDefinitionRequest(
            String fieldId, FieldType type, FieldCardinality cardinality, FieldRequiredness requiredness, BindingRequest binding) {
        FieldDefinition toDomain() {
            if (fieldId == null || fieldId.isBlank()) {
                throw new MalformedTemplateRequestException("A field definition requires a non-blank fieldId.");
            }
            if (type == null || cardinality == null || requiredness == null || binding == null) {
                throw new MalformedTemplateRequestException(
                        "Field \"" + fieldId + "\" requires type, cardinality, requiredness, and binding.");
            }
            return new FieldDefinition(fieldId, type, cardinality, requiredness, binding.toDomain());
        }
    }

    record FieldDefinitionResponse(
            String fieldId,
            String type,
            String cardinality,
            String requiredness,
            String bindingKind,
            String tag,
            String part,
            String nodeId) {
        static FieldDefinitionResponse from(FieldDefinition field) {
            return switch (field.binding()) {
                case FieldBindingTarget.ContentControlTag(String tag) -> new FieldDefinitionResponse(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "CONTENT_CONTROL_TAG", tag, null, null);
                case FieldBindingTarget.StructuralNode(DocumentPartKind part, String nodeId) -> new FieldDefinitionResponse(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "STRUCTURAL_NODE", null, part.name(), nodeId);
            };
        }
    }

    record TemplateResponse(long id, String displayName, String status, Long currentActiveVersionId, OffsetDateTime createdAt) {
        static TemplateResponse from(Template template) {
            return new TemplateResponse(
                    template.id(), template.displayName(), template.status().name(), template.currentActiveVersionId(), template.createdAt());
        }
    }

    record TemplateVersionResponse(
            long id,
            long templateId,
            int versionNumber,
            long sourceArtifactId,
            long extractionVersionId,
            String status,
            List<FieldDefinitionResponse> fields,
            OffsetDateTime createdAt,
            OffsetDateTime activatedAt) {
        static TemplateVersionResponse from(TemplateVersion version) {
            return new TemplateVersionResponse(
                    version.id(),
                    version.templateId(),
                    version.versionNumber(),
                    version.sourceArtifactId(),
                    version.extractionVersionId(),
                    version.status().name(),
                    version.fieldDefinitions().stream().map(FieldDefinitionResponse::from).toList(),
                    version.createdAt(),
                    version.activatedAt());
        }
    }

    record TemplateDraftResponse(TemplateResponse template, TemplateVersionResponse draftVersion) {
        static TemplateDraftResponse from(Template template, TemplateVersion draft) {
            return new TemplateDraftResponse(TemplateResponse.from(template), TemplateVersionResponse.from(draft));
        }
    }
}
