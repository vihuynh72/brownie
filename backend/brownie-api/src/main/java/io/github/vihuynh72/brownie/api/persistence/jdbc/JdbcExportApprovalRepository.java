package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.export.ExportApproval;
import io.github.vihuynh72.brownie.core.export.ExportApprovalRepository;
import io.github.vihuynh72.brownie.core.export.ExportFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/** JDBC persistence for export approvals, mirroring {@code JdbcValidationRepository}'s own shape. */
@Repository
class JdbcExportApprovalRepository implements ExportApprovalRepository {

    private static final String COLUMNS =
            "id, workspace_id, document_id, revision_id, template_version_id, validation_manifest_id, format, actor_user_id, approved_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcExportApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ExportApproval save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateVersionId,
            long validationManifestId,
            ExportFormat format) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO export_approval
                    (workspace_id, document_id, revision_id, template_version_id, validation_manifest_id, format, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING\
                """ + " " + COLUMNS,
                this::mapApproval,
                workspaceId,
                documentId,
                revisionId,
                templateVersionId,
                validationManifestId,
                format.name(),
                userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExportApproval> findLatest(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM export_approval WHERE workspace_id = ? AND document_id = ? "
                                + "ORDER BY approved_at DESC LIMIT 1",
                        this::mapApproval,
                        workspaceId,
                        documentId)
                .stream()
                .findFirst();
    }

    private ExportApproval mapApproval(ResultSet rs, int rowNum) throws SQLException {
        return new ExportApproval(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getLong("revision_id"),
                rs.getLong("template_version_id"),
                rs.getLong("validation_manifest_id"),
                ExportFormat.valueOf(rs.getString("format")),
                rs.getLong("actor_user_id"),
                rs.getObject("approved_at", OffsetDateTime.class));
    }
}
