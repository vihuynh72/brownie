package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.EmptyValueResolution;
import io.github.vihuynh72.brownie.core.rule.OverflowResolution;
import io.github.vihuynh72.brownie.core.rule.RuleCategory;
import io.github.vihuynh72.brownie.core.rule.RuleNotFoundException;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.rule.RuleService;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.MalformedTemplateRequestException;
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
 * Proposes and decides rules against a template's own current draft
 * version. A rule is immutable once proposed -- "editing" one is this same
 * decide-then-repropose composition, not a separate mutation -- see {@code
 * RuleService#rejectRule}'s own javadoc. Lives alongside {@link
 * TemplateController} (same package) rather than its own, since a rule
 * has no identity independent of the template draft it was proposed
 * against, and reuses that class's own {@code BindingRequest} for a
 * {@code ProtectedRegion} rule's target.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/templates/{templateId}/rules")
class RuleController {

    private final RuleService ruleService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    RuleController(
            RuleService ruleService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.ruleService = ruleService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RuleResponse propose(
            @PathVariable long workspaceId,
            @PathVariable long templateId,
            @RequestBody ProposeRuleRequest request,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        RuleRevision rule = ruleService.propose(
                workspaceId, userId, templateId, request.scope().toDomain(), request.payload().toDomain(), request.humanExplanation());
        return RuleResponse.from(rule);
    }

    /** Every rule revision proposed against the template's current draft version, in the order proposed. */
    @GetMapping
    List<RuleResponse> findAll(@PathVariable long workspaceId, @PathVariable long templateId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return ruleService.findForDraft(workspaceId, userId, templateId).stream().map(RuleResponse::from).toList();
    }

    @GetMapping("/{ruleId}")
    RuleResponse find(
            @PathVariable long workspaceId,
            @PathVariable long templateId,
            @PathVariable long ruleId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return RuleResponse.from(ruleService
                .find(workspaceId, userId, templateId, ruleId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId)));
    }

    /** Accepting a {@code ProtectedRegion} rule is this codebase's own "lock a region" action -- see {@code RuleService#acceptRule}'s own javadoc. */
    @PostMapping("/{ruleId}/accept")
    RuleResponse accept(
            @PathVariable long workspaceId,
            @PathVariable long templateId,
            @PathVariable long ruleId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return RuleResponse.from(ruleService.acceptRule(workspaceId, userId, templateId, ruleId));
    }

    /** "Editing" a rejected rule is proposing a fresh one with the corrected payload -- there is no separate edit route. */
    @PostMapping("/{ruleId}/reject")
    RuleResponse reject(
            @PathVariable long workspaceId,
            @PathVariable long templateId,
            @PathVariable long ruleId,
            @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_TEMPLATES);
        return RuleResponse.from(ruleService.rejectRule(workspaceId, userId, templateId, ruleId));
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

    record ProposeRuleRequest(RuleScopeRequest scope, RulePayloadRequest payload, String humanExplanation) {
    }

    record RuleScopeRequest(String kind, String fieldId) {
        RuleScope toDomain() {
            if (kind == null) {
                throw new MalformedTemplateRequestException("A rule scope requires a kind.");
            }
            return switch (kind) {
                case "WHOLE_TEMPLATE" -> new RuleScope.WholeTemplate();
                case "SINGLE_FIELD" -> {
                    if (fieldId == null || fieldId.isBlank()) {
                        throw new MalformedTemplateRequestException("A SINGLE_FIELD scope requires a non-blank fieldId.");
                    }
                    yield new RuleScope.SingleField(fieldId);
                }
                default -> throw new MalformedTemplateRequestException("scope kind must be WHOLE_TEMPLATE or SINGLE_FIELD.");
            };
        }

        static RuleScopeRequest from(RuleScope scope) {
            return switch (scope) {
                case RuleScope.WholeTemplate ignored -> new RuleScopeRequest("WHOLE_TEMPLATE", null);
                case RuleScope.SingleField(String fieldId) -> new RuleScopeRequest("SINGLE_FIELD", fieldId);
            };
        }
    }

    /**
     * One flat shape for all ten {@link RulePayload} kinds, the same
     * "only the sub-fields the chosen kind actually needs are required"
     * convention {@code TemplateController.BindingRequest} already
     * establishes for a field binding's own two kinds.
     */
    record RulePayloadRequest(
            String kind,
            String fieldId,
            List<String> fieldIds,
            Integer maxCharacters,
            Integer maxItems,
            List<String> orderedSectionIds,
            String dateFormatStyle,
            List<String> allowedSourceKinds,
            String emptyValueResolution,
            String overflowResolution,
            TemplateController.BindingRequest protectedRegionTarget) {

        RulePayload toDomain() {
            if (kind == null) {
                throw new MalformedTemplateRequestException("A rule payload requires a kind.");
            }
            return switch (kind) {
                case "REQUIRED_FIELDS" -> new RulePayload.RequiredFields(requireList(fieldIds, "fieldIds"));
                case "MAX_TEXT_LENGTH" -> new RulePayload.MaxTextLength(
                        requireFieldId(), requireInt(maxCharacters, "maxCharacters"));
                case "MAX_ITEM_COUNT" -> new RulePayload.MaxItemCount(requireFieldId(), requireInt(maxItems, "maxItems"));
                case "ALLOWED_SECTION_ORDER" -> new RulePayload.AllowedSectionOrder(requireList(orderedSectionIds, "orderedSectionIds"));
                case "DATE_DISPLAY_FORMAT" -> new RulePayload.DateDisplayFormat(requireFieldId(), requireEnum(DateFormatStyle.class, dateFormatStyle, "dateFormatStyle"));
                case "ALLOWED_SOURCE_KINDS" -> new RulePayload.AllowedSourceKinds(
                        requireFieldId(),
                        requireList(allowedSourceKinds, "allowedSourceKinds").stream()
                                .map(value -> requireEnum(SourceKind.class, value, "allowedSourceKinds"))
                                .toList());
                case "MISSING_VALUE_BEHAVIOR" -> new RulePayload.MissingValueBehavior(
                        requireFieldId(), requireEnum(EmptyValueResolution.class, emptyValueResolution, "emptyValueResolution"));
                case "ALLOWED_OVERFLOW_BEHAVIOR" -> new RulePayload.AllowedOverflowBehavior(
                        requireFieldId(), requireEnum(OverflowResolution.class, overflowResolution, "overflowResolution"));
                case "REPEATABLE_REGION_EMPTY_BEHAVIOR" -> new RulePayload.RepeatableRegionEmptyBehavior(
                        requireFieldId(), requireEnum(EmptyValueResolution.class, emptyValueResolution, "emptyValueResolution"));
                case "PROTECTED_REGION" -> {
                    if (protectedRegionTarget == null) {
                        throw new MalformedTemplateRequestException("A PROTECTED_REGION payload requires protectedRegionTarget.");
                    }
                    yield new RulePayload.ProtectedRegion(protectedRegionTarget.toDomain());
                }
                default -> throw new MalformedTemplateRequestException("Unrecognized rule payload kind \"" + kind + "\".");
            };
        }

        private String requireFieldId() {
            if (fieldId == null || fieldId.isBlank()) {
                throw new MalformedTemplateRequestException("Payload kind \"" + kind + "\" requires a non-blank fieldId.");
            }
            return fieldId;
        }

        private static List<String> requireList(List<String> values, String property) {
            if (values == null || values.isEmpty()) {
                throw new MalformedTemplateRequestException(property + " must contain at least one entry.");
            }
            return values;
        }

        private static int requireInt(Integer value, String property) {
            if (value == null) {
                throw new MalformedTemplateRequestException(property + " is required for this payload kind.");
            }
            return value;
        }

        private static <E extends Enum<E>> E requireEnum(Class<E> type, String value, String property) {
            if (value == null) {
                throw new MalformedTemplateRequestException(property + " is required for this payload kind.");
            }
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException e) {
                throw new MalformedTemplateRequestException(property + " has an unrecognized value \"" + value + "\".");
            }
        }

        static RulePayloadRequest from(RulePayload payload) {
            return switch (payload) {
                case RulePayload.RequiredFields(List<String> fieldIds) ->
                        new RulePayloadRequest("REQUIRED_FIELDS", null, fieldIds, null, null, null, null, null, null, null, null);
                case RulePayload.MaxTextLength(String fieldId, int maxCharacters) ->
                        new RulePayloadRequest("MAX_TEXT_LENGTH", fieldId, null, maxCharacters, null, null, null, null, null, null, null);
                case RulePayload.MaxItemCount(String fieldId, int maxItems) ->
                        new RulePayloadRequest("MAX_ITEM_COUNT", fieldId, null, null, maxItems, null, null, null, null, null, null);
                case RulePayload.AllowedSectionOrder(List<String> orderedSectionIds) ->
                        new RulePayloadRequest("ALLOWED_SECTION_ORDER", null, null, null, null, orderedSectionIds, null, null, null, null, null);
                case RulePayload.DateDisplayFormat(String fieldId, DateFormatStyle style) ->
                        new RulePayloadRequest("DATE_DISPLAY_FORMAT", fieldId, null, null, null, null, style.name(), null, null, null, null);
                case RulePayload.AllowedSourceKinds(String fieldId, List<SourceKind> allowedKinds) ->
                        new RulePayloadRequest(
                                "ALLOWED_SOURCE_KINDS", fieldId, null, null, null, null, null,
                                allowedKinds.stream().map(Enum::name).toList(), null, null, null);
                case RulePayload.MissingValueBehavior(String fieldId, EmptyValueResolution resolution) ->
                        new RulePayloadRequest("MISSING_VALUE_BEHAVIOR", fieldId, null, null, null, null, null, null, resolution.name(), null, null);
                case RulePayload.AllowedOverflowBehavior(String fieldId, OverflowResolution resolution) ->
                        new RulePayloadRequest("ALLOWED_OVERFLOW_BEHAVIOR", fieldId, null, null, null, null, null, null, null, resolution.name(), null);
                case RulePayload.RepeatableRegionEmptyBehavior(String fieldId, EmptyValueResolution resolution) ->
                        new RulePayloadRequest("REPEATABLE_REGION_EMPTY_BEHAVIOR", fieldId, null, null, null, null, null, null, resolution.name(), null, null);
                case RulePayload.ProtectedRegion(FieldBindingTarget target) ->
                        new RulePayloadRequest(
                                "PROTECTED_REGION", null, null, null, null, null, null, null, null, null, bindingRequestFrom(target));
            };
        }

        private static TemplateController.BindingRequest bindingRequestFrom(FieldBindingTarget target) {
            return switch (target) {
                case FieldBindingTarget.ContentControlTag(String tag) ->
                        new TemplateController.BindingRequest(TemplateController.BindingKind.CONTENT_CONTROL_TAG, tag, null, null);
                case FieldBindingTarget.StructuralNode(var part, String nodeId) ->
                        new TemplateController.BindingRequest(TemplateController.BindingKind.STRUCTURAL_NODE, null, part, nodeId);
            };
        }
    }

    record RuleResponse(
            long id,
            long templateId,
            long templateVersionId,
            String category,
            RuleScopeRequest scope,
            RulePayloadRequest payload,
            String status,
            String humanExplanation,
            long authorUserId,
            OffsetDateTime createdAt) {
        static RuleResponse from(RuleRevision rule) {
            RuleCategory category = rule.category();
            return new RuleResponse(
                    rule.id(),
                    rule.templateId(),
                    rule.templateVersionId(),
                    category.name(),
                    RuleScopeRequest.from(rule.scope()),
                    RulePayloadRequest.from(rule.payload()),
                    rule.status().name(),
                    rule.humanExplanation(),
                    rule.authorUserId(),
                    rule.createdAt());
        }
    }
}
