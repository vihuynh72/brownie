package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.validation.ValidationFinding;
import io.github.vihuynh72.brownie.core.validation.ValidationManifest;
import io.github.vihuynh72.brownie.core.validation.ValidationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

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
            Long docxArtifactId,
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
                rs.getObject("docx_artifact_id", Long.class),
                rs.getString("docx_sha256"),
                pdfArtifactId,
                rs.getString("pdf_sha256"),
                fromJson(rs.getString("findings")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String toJson(List<ValidationFinding> findings) {
        return ValidationFindingJson.encode(objectMapper, findings);
    }

    private List<ValidationFinding> fromJson(String json) {
        return ValidationFindingJson.decode(objectMapper, json);
    }
}
