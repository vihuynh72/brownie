package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.template.PreparedDerivation;
import io.github.vihuynh72.brownie.core.template.TemplateLineageRepository;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC persistence for template versions made from other versions. A made
 * version is written straight as ACTIVATED (the insert policy has no status
 * check, and a made version never has a draft), its rules are copied with
 * their statuses, and the template's pointer moves only from the version it
 * was made from. Every write joins the caller's transaction, whose first
 * statement here locks the template's row.
 *
 * <p>Field definitions and rules are turned into JSON by the same code that
 * reads and writes them for {@link JdbcTemplateRepository} and {@link
 * JdbcRuleRepository}, so a made version's rows read back exactly like any
 * other's.
 */
@Repository
class JdbcTemplateLineageRepository implements TemplateLineageRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final JdbcTemplateRepository templateRepository;

    JdbcTemplateLineageRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, JdbcTemplateRepository templateRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.templateRepository = templateRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findFieldIdsEverUsed(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return new HashSet<>(jdbcTemplate.queryForList(
                """
                SELECT DISTINCT field ->> 'fieldId'
                FROM template_version v, jsonb_array_elements(v.field_definitions) field
                WHERE v.workspace_id = ? AND v.template_id = ? AND field ->> 'fieldId' IS NOT NULL
                """,
                String.class,
                workspaceId,
                templateId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TemplateVersion> findDerived(long workspaceId, long userId, long templateId, long baseVersionId, String derivationKey) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + JdbcTemplateRepository.VERSION_COLUMNS + " FROM template_version "
                                + "WHERE workspace_id = ? AND template_id = ? AND derived_from_version_id = ? AND derivation_key = ?",
                        templateRepository::mapVersion,
                        workspaceId,
                        templateId,
                        baseVersionId,
                        derivationKey)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public Long lockTemplate(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<Long[]> rows = jdbcTemplate.query(
                "SELECT current_active_version_id FROM template WHERE workspace_id = ? AND id = ? FOR UPDATE",
                (rs, rowNum) -> {
                    long current = rs.getLong("current_active_version_id");
                    return new Long[] {rs.wasNull() ? null : current};
                },
                workspaceId,
                templateId);
        if (rows.isEmpty()) {
            throw new TemplateNotFoundException(templateId);
        }
        return rows.getFirst()[0];
    }

    @Override
    @Transactional
    public TemplateVersion insertDerived(long workspaceId, long userId, PreparedDerivation prepared) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // A version is made from one of its own kind: a Word version pinned to its Word reading, or a PDF version pinned to the
        // base's PDF form reading. It carries on the notes kept from the upload the template was made from.
        TemplateVersion inserted = jdbcTemplate.queryForObject(
                """
                INSERT INTO template_version (
                    workspace_id, template_id, version_number, source_artifact_id, kind, extraction_version_id, pdf_form_extraction_id,
                    status, field_definitions, activated_at, derived_from_version_id, derivation_key, derivation, preparation_notices)
                SELECT ?, ?, COALESCE(MAX(v.version_number), 0) + 1, ?, ?, ?::bigint, ?::bigint, 'ACTIVATED', ?::jsonb, now(), ?, ?, ?::jsonb,
                       (SELECT b.preparation_notices FROM template_version b WHERE b.workspace_id = ? AND b.id = ?)
                FROM template_version v
                WHERE v.workspace_id = ? AND v.template_id = ?
                RETURNING """
                        + " " + JdbcTemplateRepository.VERSION_COLUMNS,
                templateRepository::mapVersion,
                workspaceId,
                prepared.templateId(),
                prepared.sourceArtifactId(),
                prepared.kind().name(),
                prepared.extractionVersionId(),
                prepared.pdfFormExtractionId(),
                templateRepository.toJson(prepared.fieldDefinitions()),
                prepared.baseVersionId(),
                prepared.derivationKey(),
                prepared.derivationJson(),
                workspaceId,
                prepared.baseVersionId(),
                workspaceId,
                prepared.templateId());
        for (RuleRevision rule : prepared.rules()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO rule_revision
                        (workspace_id, template_id, template_version_id, category, scope, payload, schema_version, status,
                         human_explanation, author_user_id)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                    """,
                    workspaceId,
                    prepared.templateId(),
                    inserted.id(),
                    rule.payload().category().name(),
                    toJson(JdbcRuleRepository.scopeToMap(rule.scope())),
                    toJson(JdbcRuleRepository.payloadToMap(rule.payload())),
                    rule.schemaVersion(),
                    rule.status().name(),
                    rule.humanExplanation(),
                    rule.authorUserId());
        }
        int moved = jdbcTemplate.update(
                "UPDATE template SET current_active_version_id = ? WHERE workspace_id = ? AND id = ? AND current_active_version_id = ?",
                inserted.id(),
                workspaceId,
                prepared.templateId(),
                prepared.baseVersionId());
        if (moved != 1) {
            throw new IllegalStateException(
                    "Template " + prepared.templateId() + " left version " + prepared.baseVersionId() + " while its row was locked.");
        }
        Map<String, Integer> kinds = new LinkedHashMap<>();
        prepared.changeKinds().forEach(kind -> kinds.merge(kind, 1, Integer::sum));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("templateId", prepared.templateId());
        details.put("baseVersionId", prepared.baseVersionId());
        details.put("versionId", inserted.id());
        details.put("changeKinds", kinds);
        details.put("count", prepared.changeKinds().size());
        // IDs and counts only: a label is something a person wrote.
        AuditWriter.append(jdbcTemplate, workspaceId, userId, "TEMPLATE_VERSION_DERIVED", "template-version", inserted.id(), toJson(details));
        return inserted;
    }

    @Override
    @Transactional
    public void advanceToDerived(long workspaceId, long userId, long templateId, long baseVersionId, long derivedVersionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        int moved = jdbcTemplate.update(
                """
                UPDATE template t
                SET current_active_version_id = ?
                WHERE t.workspace_id = ? AND t.id = ? AND t.current_active_version_id = ?
                  AND EXISTS (
                      SELECT 1 FROM template_version v
                      WHERE v.workspace_id = t.workspace_id AND v.template_id = t.id AND v.id = ?
                        AND v.derived_from_version_id = ? AND v.status = 'ACTIVATED'
                  )
                """,
                derivedVersionId,
                workspaceId,
                templateId,
                baseVersionId,
                derivedVersionId,
                baseVersionId);
        if (moved != 1) {
            throw new IllegalStateException(
                    "Template " + templateId + " could not go on from version " + baseVersionId + " to " + derivedVersionId + ".");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findChangedFieldIds(long workspaceId, long userId, long templateVersionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<String> derivations = jdbcTemplate.queryForList(
                "SELECT derivation::text FROM template_version WHERE workspace_id = ? AND id = ? AND derivation IS NOT NULL",
                String.class,
                workspaceId,
                templateVersionId);
        List<String> fieldIds = new ArrayList<>();
        if (derivations.isEmpty()) {
            return fieldIds;
        }
        try {
            JsonNode changes = objectMapper.readTree(derivations.getFirst()).get("changes");
            if (changes != null) {
                for (JsonNode change : changes) {
                    JsonNode fieldId = change.get("fieldId");
                    if (fieldId != null && fieldId.isTextual()) {
                        fieldIds.add(fieldId.asText());
                    }
                }
            }
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored derivation of template version " + templateVersionId + " is not valid JSON.", e);
        }
        return fieldIds;
    }

    @Override
    @Transactional(readOnly = true)
    public int countLiveDocumentsOn(long workspaceId, long userId, long templateVersionId, long exceptDocumentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document WHERE workspace_id = ? AND template_version_id = ? AND id <> ? AND trashed_at IS NULL",
                Integer.class,
                workspaceId,
                templateVersionId,
                exceptDocumentId);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public boolean stepCurrentVersion(long workspaceId, long userId, long templateId, long fromVersionId, long toVersionId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.update(
                """
                UPDATE template t
                SET current_active_version_id = ?
                WHERE t.workspace_id = ? AND t.id = ? AND t.current_active_version_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM template_version f
                      JOIN template_version tv ON tv.workspace_id = f.workspace_id AND tv.template_id = f.template_id
                      WHERE f.workspace_id = t.workspace_id AND f.template_id = t.id AND f.id = ? AND tv.id = ?
                        AND tv.status = 'ACTIVATED'
                        AND (f.derived_from_version_id = tv.id OR tv.derived_from_version_id = f.id)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM document d
                      WHERE d.workspace_id = t.workspace_id AND d.template_version_id = ? AND d.id <> ? AND d.trashed_at IS NULL
                  )
                """,
                toVersionId,
                workspaceId,
                templateId,
                fromVersionId,
                fromVersionId,
                toVersionId,
                fromVersionId,
                documentId) == 1;
    }

    @Override
    @Transactional
    public void recordDocumentVersionChanged(
            long workspaceId, long userId, long documentId, long fromVersionId, long toVersionId, long revisionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "DOCUMENT_TEMPLATE_VERSION_CHANGED", "document", documentId,
                "{\"fromVersionId\":" + fromVersionId + ",\"toVersionId\":" + toVersionId + ",\"revisionId\":" + revisionId + "}");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + value.getClass().getSimpleName() + " to JSON.", e);
        }
    }
}
