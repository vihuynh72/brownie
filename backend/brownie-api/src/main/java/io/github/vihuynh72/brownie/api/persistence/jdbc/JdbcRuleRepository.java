package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.rule.DateFormatStyle;
import io.github.vihuynh72.brownie.core.rule.EmptyValueResolution;
import io.github.vihuynh72.brownie.core.rule.OverflowResolution;
import io.github.vihuynh72.brownie.core.rule.RuleCategory;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleRevisionStatus;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.rule.RuleTemplateVersionStateException;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code brownie-core} keeps {@link RuleScope} and {@link RulePayload} plain
 * sealed interfaces with no framework dependency, the same framework-free
 * boundary {@code JdbcTemplateRepository} already keeps for {@code
 * FieldBindingTarget} -- so this repository owns turning them into JSON by
 * hand, as a small {@code Map<String, Object>} tree with a {@code kind}
 * discriminator, rather than through Jackson's own polymorphic-type
 * annotations. The binding-target sub-mapping is deliberately duplicated
 * from {@code JdbcTemplateRepository}'s own private {@code
 * FieldDefinitionJson} rather than shared -- small, and not worth reopening
 * that already-verified class for.
 */
@Repository
class JdbcRuleRepository implements RuleRepository {

    private static final String COLUMNS =
            "id, workspace_id, template_id, template_version_id, category, scope, payload, schema_version, status, "
                    + "human_explanation, author_user_id, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcRuleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public RuleRevision propose(
            long workspaceId,
            long userId,
            long templateId,
            long templateVersionId,
            RuleScope scope,
            RulePayload payload,
            String schemaVersion,
            String humanExplanation) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // The service checks this state before validation, but this query
        // repeats the guard atomically with the insert. Otherwise a caller
        // that reaches the repository after activation could add a rule to
        // an immutable version, or pair one template with another version.
        List<Long> ids = jdbcTemplate.queryForList(
                """
                INSERT INTO rule_revision
                    (workspace_id, template_id, template_version_id, category, scope, payload, schema_version, human_explanation, author_user_id)
                SELECT ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?
                WHERE EXISTS (
                    SELECT 1
                    FROM template_version
                    WHERE workspace_id = ? AND template_id = ? AND id = ? AND status = 'DRAFT'
                )
                RETURNING id
                """,
                Long.class,
                workspaceId,
                templateId,
                templateVersionId,
                payload.category().name(),
                toJson(scopeToMap(scope)),
                toJson(payloadToMap(payload)),
                schemaVersion,
                humanExplanation,
                userId,
                workspaceId,
                templateId,
                templateVersionId);
        if (ids.isEmpty()) {
            throw new RuleTemplateVersionStateException(
                    "Template " + templateId + " version " + templateVersionId + " is not an open draft version.");
        }
        long id = ids.get(0);
        return find(workspaceId, userId, templateId, id)
                .orElseThrow(() -> new IllegalStateException("Rule revision " + id + " vanished after proposing it."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RuleRevision> find(long workspaceId, long userId, long templateId, long ruleId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + COLUMNS + " FROM rule_revision WHERE workspace_id = ? AND template_id = ? AND id = ?",
                        this::mapRow,
                        workspaceId,
                        templateId,
                        ruleId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleRevision> findByTemplateVersion(long workspaceId, long userId, long templateVersionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM rule_revision WHERE workspace_id = ? AND template_version_id = ? ORDER BY id",
                this::mapRow,
                workspaceId,
                templateVersionId);
    }

    private RuleRevision mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RuleRevision(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("template_id"),
                rs.getLong("template_version_id"),
                RuleCategory.valueOf(rs.getString("category")),
                scopeFromMap(fromJson(rs.getString("scope"))),
                payloadFromMap(fromJson(rs.getString("payload"))),
                rs.getString("schema_version"),
                RuleRevisionStatus.valueOf(rs.getString("status")),
                rs.getString("human_explanation"),
                rs.getLong("author_user_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static Map<String, Object> scopeToMap(RuleScope scope) {
        return switch (scope) {
            case RuleScope.WholeTemplate() -> Map.of("kind", "WHOLE_TEMPLATE");
            case RuleScope.SingleField(String fieldId) -> Map.of("kind", "SINGLE_FIELD", "fieldId", fieldId);
        };
    }

    private static RuleScope scopeFromMap(Map<String, Object> map) {
        return switch ((String) map.get("kind")) {
            case "WHOLE_TEMPLATE" -> new RuleScope.WholeTemplate();
            case "SINGLE_FIELD" -> new RuleScope.SingleField((String) map.get("fieldId"));
            default -> throw new IllegalStateException("Unknown stored rule scope kind: " + map.get("kind"));
        };
    }

    private static Map<String, Object> payloadToMap(RulePayload payload) {
        return switch (payload) {
            case RulePayload.RequiredFields(List<String> fieldIds) -> Map.of("kind", "REQUIRED_FIELDS", "fieldIds", fieldIds);
            case RulePayload.MaxTextLength(String fieldId, int maxCharacters) ->
                    Map.of("kind", "MAX_TEXT_LENGTH", "fieldId", fieldId, "maxCharacters", maxCharacters);
            case RulePayload.MaxItemCount(String fieldId, int maxItems) ->
                    Map.of("kind", "MAX_ITEM_COUNT", "fieldId", fieldId, "maxItems", maxItems);
            case RulePayload.AllowedSectionOrder(List<String> orderedSectionIds) ->
                    Map.of("kind", "ALLOWED_SECTION_ORDER", "orderedSectionIds", orderedSectionIds);
            case RulePayload.DateDisplayFormat(String fieldId, DateFormatStyle style) ->
                    Map.of("kind", "DATE_DISPLAY_FORMAT", "fieldId", fieldId, "style", style.name());
            case RulePayload.AllowedSourceKinds(String fieldId, List<SourceKind> allowedKinds) -> Map.of(
                    "kind", "ALLOWED_SOURCE_KINDS", "fieldId", fieldId, "allowedKinds",
                    allowedKinds.stream().map(Enum::name).toList());
            case RulePayload.MissingValueBehavior(String fieldId, EmptyValueResolution resolution) ->
                    Map.of("kind", "MISSING_VALUE_BEHAVIOR", "fieldId", fieldId, "resolution", resolution.name());
            case RulePayload.AllowedOverflowBehavior(String fieldId, OverflowResolution resolution) ->
                    Map.of("kind", "ALLOWED_OVERFLOW_BEHAVIOR", "fieldId", fieldId, "resolution", resolution.name());
            case RulePayload.RepeatableRegionEmptyBehavior(String fieldId, EmptyValueResolution resolution) ->
                    Map.of("kind", "REPEATABLE_REGION_EMPTY_BEHAVIOR", "fieldId", fieldId, "resolution", resolution.name());
            case RulePayload.ProtectedRegion(FieldBindingTarget target) -> {
                Map<String, Object> map = new java.util.HashMap<>();
                map.put("kind", "PROTECTED_REGION");
                switch (target) {
                    case FieldBindingTarget.ContentControlTag(String tag) -> {
                        map.put("bindingKind", "CONTENT_CONTROL_TAG");
                        map.put("tag", tag);
                    }
                    case FieldBindingTarget.StructuralNode(DocumentPartKind part, String nodeId) -> {
                        map.put("bindingKind", "STRUCTURAL_NODE");
                        map.put("part", part.name());
                        map.put("nodeId", nodeId);
                    }
                }
                yield map;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static RulePayload payloadFromMap(Map<String, Object> map) {
        return switch ((String) map.get("kind")) {
            case "REQUIRED_FIELDS" -> new RulePayload.RequiredFields((List<String>) map.get("fieldIds"));
            case "MAX_TEXT_LENGTH" ->
                    new RulePayload.MaxTextLength((String) map.get("fieldId"), ((Number) map.get("maxCharacters")).intValue());
            case "MAX_ITEM_COUNT" ->
                    new RulePayload.MaxItemCount((String) map.get("fieldId"), ((Number) map.get("maxItems")).intValue());
            case "ALLOWED_SECTION_ORDER" -> new RulePayload.AllowedSectionOrder((List<String>) map.get("orderedSectionIds"));
            case "DATE_DISPLAY_FORMAT" ->
                    new RulePayload.DateDisplayFormat((String) map.get("fieldId"), DateFormatStyle.valueOf((String) map.get("style")));
            case "ALLOWED_SOURCE_KINDS" -> new RulePayload.AllowedSourceKinds(
                    (String) map.get("fieldId"),
                    ((List<String>) map.get("allowedKinds")).stream().map(SourceKind::valueOf).toList());
            case "MISSING_VALUE_BEHAVIOR" -> new RulePayload.MissingValueBehavior(
                    (String) map.get("fieldId"), EmptyValueResolution.valueOf((String) map.get("resolution")));
            case "ALLOWED_OVERFLOW_BEHAVIOR" -> new RulePayload.AllowedOverflowBehavior(
                    (String) map.get("fieldId"), OverflowResolution.valueOf((String) map.get("resolution")));
            case "REPEATABLE_REGION_EMPTY_BEHAVIOR" -> new RulePayload.RepeatableRegionEmptyBehavior(
                    (String) map.get("fieldId"), EmptyValueResolution.valueOf((String) map.get("resolution")));
            case "PROTECTED_REGION" -> new RulePayload.ProtectedRegion(bindingTargetFromMap(map));
            default -> throw new IllegalStateException("Unknown stored rule payload kind: " + map.get("kind"));
        };
    }

    private static FieldBindingTarget bindingTargetFromMap(Map<String, Object> map) {
        return switch ((String) map.get("bindingKind")) {
            case "CONTENT_CONTROL_TAG" -> new FieldBindingTarget.ContentControlTag((String) map.get("tag"));
            case "STRUCTURAL_NODE" ->
                    new FieldBindingTarget.StructuralNode(DocumentPartKind.valueOf((String) map.get("part")), (String) map.get("nodeId"));
            default -> throw new IllegalStateException("Unknown stored binding kind: " + map.get("bindingKind"));
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName() + " to JSON.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored rule JSON.", e);
        }
    }
}
