package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.compile.CompilationManifest;
import io.github.vihuynh72.brownie.core.compile.CompilationRepository;
import io.github.vihuynh72.brownie.core.compile.IntegrityFinding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JDBC persistence for compilation manifests. {@link IntegrityFinding} is a
 * single closed record, not a sealed interface, so -- unlike {@code
 * JdbcRuleRepository}'s own {@code RulePayload} handling -- it needs no
 * {@code kind} discriminator, just a direct field-by-field JSON mapping.
 */
@Repository
class JdbcCompilationRepository implements CompilationRepository {

    private static final String COLUMNS =
            "id, workspace_id, document_id, revision_id, template_id, template_version_id, "
                    + "docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, renderer_version, "
                    + "integrity_findings, compiled_at";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    JdbcCompilationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public CompilationManifest save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateId,
            long templateVersionId,
            long docxArtifactId,
            String docxSha256,
            long pdfArtifactId,
            String pdfSha256,
            String rendererVersion,
            List<IntegrityFinding> integrityFindings) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO document_compilation
                    (workspace_id, document_id, revision_id, template_id, template_version_id,
                     docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, renderer_version, integrity_findings)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
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
                rendererVersion,
                toJson(integrityFindings));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CompilationManifest> findLatest(long workspaceId, long userId, long documentId, long revisionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM document_compilation "
                                + "WHERE workspace_id = ? AND document_id = ? AND revision_id = ? "
                                + "ORDER BY compiled_at DESC LIMIT 1",
                        this::mapManifest,
                        workspaceId,
                        documentId,
                        revisionId)
                .stream()
                .findFirst();
    }

    private CompilationManifest mapManifest(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CompilationManifest(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getLong("revision_id"),
                rs.getLong("template_id"),
                rs.getLong("template_version_id"),
                rs.getLong("docx_artifact_id"),
                rs.getString("docx_sha256"),
                rs.getLong("pdf_artifact_id"),
                rs.getString("pdf_sha256"),
                rs.getString("renderer_version"),
                fromJson(rs.getString("integrity_findings")),
                rs.getObject("compiled_at", OffsetDateTime.class));
    }

    private String toJson(List<IntegrityFinding> findings) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (IntegrityFinding finding : findings) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fieldId", finding.fieldId());
            entry.put("expectedText", finding.expectedText());
            entry.put("foundInDocx", finding.foundInDocx());
            entry.put("foundInPdf", finding.foundInPdf());
            encoded.add(entry);
        }
        try {
            return objectMapper.writeValueAsString(encoded);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize compilation integrity findings.", e);
        }
    }

    private List<IntegrityFinding> fromJson(String json) {
        try {
            Object decoded = objectMapper.readValue(json, Object.class);
            if (!(decoded instanceof List<?> rawList)) {
                throw malformed("integrity findings must be a JSON array");
            }
            List<IntegrityFinding> findings = new ArrayList<>();
            for (Object rawEntry : rawList) {
                Map<String, Object> entry = objectMap(rawEntry, "integrity finding");
                if (!entry.keySet().equals(Set.of("fieldId", "expectedText", "foundInDocx", "foundInPdf"))) {
                    throw malformed("unexpected properties in a stored integrity finding");
                }
                findings.add(new IntegrityFinding(
                        requiredString(entry, "fieldId"),
                        requiredString(entry, "expectedText"),
                        requiredBoolean(entry, "foundInDocx"),
                        requiredBoolean(entry, "foundInPdf")));
            }
            return findings;
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize stored compilation integrity findings.", e);
        }
    }

    /**
     * {@code JdbcDocumentRepository}'s own {@code fromJson} uses this same
     * instanceof-checked shape specifically so a subtly malformed stored
     * value (the wrong JSON type for a property, not just a missing or
     * extra one) fails with this class's own {@code malformed(...)}-wrapped
     * {@code IllegalStateException} rather than an unchecked {@code
     * ClassCastException} or {@code NullPointerException} escaping from a
     * raw cast -- this mirrors that same defensive re-read of stored JSON.
     */
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

    private static String requiredString(Map<String, Object> entry, String key) {
        if (!(entry.get(key) instanceof String value)) {
            throw malformed("expected integrity finding property " + key + " to be a string");
        }
        return value;
    }

    private static boolean requiredBoolean(Map<String, Object> entry, String key) {
        if (!(entry.get(key) instanceof Boolean value)) {
            throw malformed("expected integrity finding property " + key + " to be a boolean");
        }
        return value;
    }

    private static IllegalStateException malformed(String detail) {
        return new IllegalStateException("Stored compilation integrity findings are invalid: " + detail);
    }
}
