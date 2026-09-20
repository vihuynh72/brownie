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
        // Approving what is already the document's latest approval is one decision however many times the request
        // arrives: a second click, or a retry after a lost response, gets the approval that already exists. Only
        // the latest counts as a repeat. The latest approval is what gets exported, so someone who approved both
        // formats, then Word alone, then both again has made three decisions, and answering the third with the
        // first would leave Word alone in force. The lock makes two arriving together take turns, so the second
        // finds what the first wrote.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('export_approval:' || ?::text, 0))", rs -> null, documentId);
        Optional<ExportApproval> latest = latestFor(workspaceId, documentId);
        if (latest.isPresent()
                && latest.get().validationManifestId() == validationManifestId
                && latest.get().format() == format
                && latest.get().actorUserId() == userId) {
            return latest.get();
        }
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO export_approval
                    (workspace_id, document_id, revision_id, template_version_id, validation_manifest_id, format, actor_user_id,
                     approved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp())
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
        return latestFor(workspaceId, documentId);
    }

    /**
     * By id, not by time. Approvals of one document are written one at a time, under the lock, so their ids are in
     * the order the decisions were taken; a clock can be set back between two of them, and the one the person had
     * moved away from would then be the one exported.
     */
    private Optional<ExportApproval> latestFor(long workspaceId, long documentId) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM export_approval WHERE workspace_id = ? AND document_id = ? "
                                + "ORDER BY id DESC LIMIT 1",
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
