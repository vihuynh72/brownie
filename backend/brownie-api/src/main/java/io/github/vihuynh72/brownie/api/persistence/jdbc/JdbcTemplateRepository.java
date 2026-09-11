package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateStatus;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStateConflictException;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code brownie-core} keeps {@link FieldBindingTarget} a plain sealed
 * interface with no framework dependency of its own (the same
 * framework-free boundary every core package holds), so this repository --
 * not core -- owns turning it into JSON: a private {@code
 * FieldDefinitionJson} row shape with a flat {@code bindingKind}
 * discriminator, converted by hand rather than through Jackson's own
 * polymorphic-type annotations. {@link ObjectMapper} is {@code
 * tools.jackson}, not {@code com.fasterxml.jackson} -- see {@code
 * JdbcExtractionVersionRepository}'s own javadoc for why that distinction
 * matters on this classpath.
 */
@Repository
class JdbcTemplateRepository implements TemplateRepository {

    private static final String TEMPLATE_COLUMNS = "id, workspace_id, display_name, status, current_active_version_id, created_at";
    private static final String VERSION_COLUMNS =
            "id, workspace_id, template_id, version_number, source_artifact_id, extraction_version_id, status, field_definitions, created_at, activated_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcTemplateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Template createDraft(long workspaceId, long userId, String displayName, long sourceArtifactId, long extractionVersionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        long templateId = jdbcTemplate.queryForObject(
                "INSERT INTO template (workspace_id, display_name) VALUES (?, ?) RETURNING id",
                Long.class,
                workspaceId,
                displayName);
        jdbcTemplate.update(
                """
                INSERT INTO template_version
                    (workspace_id, template_id, version_number, source_artifact_id, extraction_version_id, field_definitions)
                VALUES (?, ?, 1, ?, ?, '[]'::jsonb)
                """,
                workspaceId,
                templateId,
                sourceArtifactId,
                extractionVersionId);
        return find(workspaceId, userId, templateId)
                .orElseThrow(() -> new IllegalStateException("Template " + templateId + " vanished after creating it."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Template> find(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + TEMPLATE_COLUMNS + " FROM template WHERE workspace_id = ? AND id = ?",
                        this::mapTemplate,
                        workspaceId,
                        templateId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateVersion> findDraftVersion(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + VERSION_COLUMNS + " FROM template_version WHERE workspace_id = ? AND template_id = ? AND status = 'DRAFT'",
                        this::mapVersion,
                        workspaceId,
                        templateId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateVersion> findVersion(long workspaceId, long userId, long templateId, long versionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + VERSION_COLUMNS + " FROM template_version WHERE workspace_id = ? AND template_id = ? AND id = ?",
                        this::mapVersion,
                        workspaceId,
                        templateId,
                        versionId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public TemplateVersion replaceDraftBindings(
            long workspaceId, long userId, long templateId, int expectedVersionNumber, List<FieldDefinition> fieldDefinitions) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<TemplateVersion> updated = jdbcTemplate.query(
                """
                UPDATE template_version
                SET field_definitions = ?::jsonb, version_number = version_number + 1
                WHERE workspace_id = ? AND template_id = ? AND version_number = ? AND status = 'DRAFT'
                RETURNING """
                        + " " + VERSION_COLUMNS,
                this::mapVersion,
                toJson(fieldDefinitions),
                workspaceId,
                templateId,
                expectedVersionNumber);
        return requireUpdated(updated, workspaceId, userId, templateId, expectedVersionNumber);
    }

    @Override
    @Transactional
    public TemplateVersion activate(long workspaceId, long userId, long templateId, int expectedVersionNumber) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<TemplateVersion> updated = jdbcTemplate.query(
                """
                UPDATE template_version
                SET status = 'ACTIVATED', activated_at = now()
                WHERE workspace_id = ? AND template_id = ? AND version_number = ? AND status = 'DRAFT'
                RETURNING """
                        + " " + VERSION_COLUMNS,
                this::mapVersion,
                workspaceId,
                templateId,
                expectedVersionNumber);
        TemplateVersion activated = requireUpdated(updated, workspaceId, userId, templateId, expectedVersionNumber);
        jdbcTemplate.update(
                "UPDATE template SET status = 'ACTIVE', current_active_version_id = ? WHERE workspace_id = ? AND id = ?",
                activated.id(),
                workspaceId,
                templateId);
        return activated;
    }

    /** Both write methods above share this: an empty result means the atomic guard failed, and the caller needs to know exactly why. */
    private TemplateVersion requireUpdated(
            List<TemplateVersion> updated, long workspaceId, long userId, long templateId, int expectedVersionNumber) {
        if (!updated.isEmpty()) {
            return updated.get(0);
        }
        if (find(workspaceId, userId, templateId).isEmpty()) {
            throw new TemplateNotFoundException(templateId);
        }
        Optional<TemplateVersion> currentDraft = findDraftVersion(workspaceId, userId, templateId);
        if (currentDraft.isEmpty()) {
            throw new TemplateVersionStateConflictException("Template " + templateId + " has no open draft version.");
        }
        throw new TemplateVersionStateConflictException(
                "Template " + templateId + " draft is at version " + currentDraft.get().versionNumber()
                        + ", not the expected " + expectedVersionNumber + ".");
    }

    private Template mapTemplate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        // wasNull() reflects only the most recent get*() call, so it must
        // be read into a local right here -- any rs.get*() call for
        // another column in between (even as a later constructor argument
        // evaluated after this one) would silently overwrite it first.
        long activeVersionId = rs.getLong("current_active_version_id");
        boolean noActiveVersion = rs.wasNull();
        return new Template(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getString("display_name"),
                TemplateStatus.valueOf(rs.getString("status")),
                noActiveVersion ? null : activeVersionId,
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private TemplateVersion mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TemplateVersion(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("template_id"),
                rs.getInt("version_number"),
                rs.getLong("source_artifact_id"),
                rs.getLong("extraction_version_id"),
                TemplateVersionStatus.valueOf(rs.getString("status")),
                fromJson(rs.getString("field_definitions")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("activated_at", OffsetDateTime.class));
    }

    private String toJson(List<FieldDefinition> fieldDefinitions) {
        try {
            return objectMapper.writeValueAsString(fieldDefinitions.stream().map(FieldDefinitionJson::from).toList());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize field definitions to JSON.", e);
        }
    }

    private List<FieldDefinition> fromJson(String json) {
        try {
            FieldDefinitionJson[] rows = objectMapper.readValue(json, FieldDefinitionJson[].class);
            return List.of(rows).stream().map(FieldDefinitionJson::toDomain).toList();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored field definitions JSON.", e);
        }
    }

    /** The JSON row shape for one {@link FieldDefinition}; only one of {@code contentControlTag} or ({@code structuralNodePart}, {@code structuralNodeId}) is set, chosen by {@code bindingKind}. */
    private record FieldDefinitionJson(
            String fieldId,
            String type,
            String cardinality,
            String requiredness,
            String bindingKind,
            String contentControlTag,
            String structuralNodePart,
            String structuralNodeId) {

        static FieldDefinitionJson from(FieldDefinition field) {
            return switch (field.binding()) {
                case FieldBindingTarget.ContentControlTag(String tag) -> new FieldDefinitionJson(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "CONTENT_CONTROL_TAG", tag, null, null);
                case FieldBindingTarget.StructuralNode(DocumentPartKind part, String nodeId) -> new FieldDefinitionJson(
                        field.fieldId(), field.type().name(), field.cardinality().name(), field.requiredness().name(),
                        "STRUCTURAL_NODE", null, part.name(), nodeId);
            };
        }

        FieldDefinition toDomain() {
            FieldBindingTarget binding = switch (bindingKind) {
                case "CONTENT_CONTROL_TAG" -> new FieldBindingTarget.ContentControlTag(contentControlTag);
                case "STRUCTURAL_NODE" -> new FieldBindingTarget.StructuralNode(DocumentPartKind.valueOf(structuralNodePart), structuralNodeId);
                default -> throw new IllegalStateException("Unknown stored binding kind: " + bindingKind);
            };
            return new FieldDefinition(
                    fieldId, FieldType.valueOf(type), FieldCardinality.valueOf(cardinality), FieldRequiredness.valueOf(requiredness), binding);
        }
    }
}
