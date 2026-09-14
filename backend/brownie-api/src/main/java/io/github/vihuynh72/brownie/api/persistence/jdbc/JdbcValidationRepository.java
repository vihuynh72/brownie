package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationFindingCode;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC persistence for validation manifests. Mirrors {@code
 * JdbcCompilationRepository}'s own shape closely; {@link ValidationFinding}
 * needs a {@code fieldId} nullability the simpler {@code IntegrityFinding}
 * did not.
 */
@Repository
class JdbcValidationRepository implements ValidationRepository {

    private static final String COLUMNS =
            "id, workspace_id, document_id, revision_id, template_id, template_version_id, "
                    + "docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, findings, created_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcValidationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ValidationManifest save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateId,
            long templateVersionId,
            long docxArtifactId,
            String docxSha256,
            Long pdfArtifactId,
            String pdfSha256,
            List<ValidationFinding> findings) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO validation_manifest
                    (workspace_id, document_id, revision_id, template_id, template_version_id,
                     docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, findings)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING\
                """ + " " + COLUMNS,
                this::mapManifest,
                workspaceId,
                documentId,
                revisionId,
                templateId,
                templateVersionId,
                docxArtifactId,
                docxSha256,
                pdfArtifactId,
                pdfSha256,
                toJson(findings));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ValidationManifest> find(long workspaceId, long userId, long documentId, long manifestId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM validation_manifest WHERE workspace_id = ? AND document_id = ? AND id = ?",
                        this::mapManifest,
                        workspaceId,
                        documentId,
                        manifestId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ValidationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM validation_manifest "
                                + "WHERE workspace_id = ? AND document_id = ? AND revision_id = ? "
                                + "ORDER BY created_at DESC LIMIT 1",
                        this::mapManifest,
                        workspaceId,
                        documentId,
                        revisionId)
                .stream()
                .findFirst();
    }

    private ValidationManifest mapManifest(ResultSet rs, int rowNum) throws SQLException {
        Long pdfArtifactId = (Long) rs.getObject("pdf_artifact_id");
        return new ValidationManifest(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getLong("revision_id"),
                rs.getLong("template_id"),
                rs.getLong("template_version_id"),
                rs.getLong("docx_artifact_id"),
                rs.getString("docx_sha256"),
                pdfArtifactId,
                rs.getString("pdf_sha256"),
                fromJson(rs.getString("findings")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String toJson(List<ValidationFinding> findings) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (ValidationFinding finding : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", finding.code().name());
            entry.put("fieldId", finding.fieldId());
            entry.put("message", finding.message());
            encoded.add(entry);
        }
        try {
            return objectMapper.writeValueAsString(encoded);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize validation findings.", e);
        }
    }

    private List<ValidationFinding> fromJson(String json) {
        try {
            Object decoded = objectMapper.readValue(json, Object.class);
            if (!(decoded instanceof List<?> rawList)) {
                throw malformed("validation findings must be a JSON array");
            }
            List<ValidationFinding> findings = new ArrayList<>();
            for (Object rawEntry : rawList) {
                Map<String, Object> entry = objectMap(rawEntry, "validation finding");
                if (!entry.keySet().equals(Set.of("code", "fieldId", "message"))) {
                    throw malformed("unexpected properties in a stored validation finding");
                }
                findings.add(new ValidationFinding(
                        requiredCode(entry, "code"),
                        optionalString(entry, "fieldId"),
                        requiredString(entry, "message")));
            }
            return findings;
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored validation findings.", e);
        }
    }

    private static Map<String, Object> objectMap(Object value, String description) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw malformed("expected " + description + " to be an object");
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw malformed("expected " + description + " keys to be strings");
            }
            mapped.put(key, entry.getValue());
        }
        return mapped;
    }

    private static ValidationFindingCode requiredCode(Map<String, Object> entry, String key) {
        if (!(entry.get(key) instanceof String value)) {
            throw malformed("expected validation finding property " + key + " to be a string");
        }
        try {
            return ValidationFindingCode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw malformed("unrecognized validation finding code " + value);
        }
    }

    private static String requiredString(Map<String, Object> entry, String key) {
        if (!(entry.get(key) instanceof String value)) {
            throw malformed("expected validation finding property " + key + " to be a string");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue)) {
            throw malformed("expected validation finding property " + key + " to be a string or null");
        }
        return stringValue;
    }

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("Stored validation findings are invalid: " + detail);
    }
}
