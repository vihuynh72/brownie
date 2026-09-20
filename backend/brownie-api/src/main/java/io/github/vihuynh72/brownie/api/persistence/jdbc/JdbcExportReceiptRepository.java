package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.export.ExportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/** JDBC persistence for export receipts, mirroring {@code JdbcExportApprovalRepository}'s own shape. */
@Repository
class JdbcExportReceiptRepository implements ExportRepository {

    private static final String COLUMNS =
            "id, workspace_id, document_id, revision_id, template_version_id, export_approval_id, validation_manifest_id, "
                    + "docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, format, actor_user_id, exported_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcExportReceiptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ExportReceipt save(
            long workspaceId,
            long userId,
            long documentId,
            long revisionId,
            long templateVersionId,
            long exportApprovalId,
            long validationManifestId,
            long docxArtifactId,
            String docxSha256,
            Long pdfArtifactId,
            String pdfSha256,
            ExportFormat format) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // One approval is exported once by the person it was exported by: a second click, or a retry after a lost
        // response, gets the receipt that already exists, and no second entry in the audit record. The files are
        // fetched through the download routes as often as anyone likes; that is not what a receipt counts.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('export_receipt:' || ?::text, 0))", rs -> null, exportApprovalId);
        Optional<ExportReceipt> existing = jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM export_receipt WHERE workspace_id = ? AND document_id = ? "
                                + "AND export_approval_id = ? AND actor_user_id = ? ORDER BY id DESC LIMIT 1",
                        this::mapReceipt,
                        workspaceId,
                        documentId,
                        exportApprovalId,
                        userId)
                .stream()
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        ExportReceipt receipt = jdbcTemplate.queryForObject(
                """
                INSERT INTO export_receipt
                    (workspace_id, document_id, revision_id, template_version_id, export_approval_id, validation_manifest_id,
                     docx_artifact_id, docx_sha256, pdf_artifact_id, pdf_sha256, format, actor_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING\
                """ + " " + COLUMNS,
                this::mapReceipt,
                workspaceId,
                documentId,
                revisionId,
                templateVersionId,
                exportApprovalId,
                validationManifestId,
                docxArtifactId,
                docxSha256,
                pdfArtifactId,
                pdfSha256,
                format.name(),
                userId);
        // An export is the moment a document leaves the system, so it is
        // recorded in the same transaction as its receipt: which revision,
        // which format, which receipt, and nothing of what it said.
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "DOCUMENT_EXPORTED", "document", documentId,
                "{\"revisionId\":" + revisionId + ",\"exportReceiptId\":" + receipt.id() + ",\"format\":\"" + format.name() + "\"}");
        return receipt;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExportReceipt> findLatest(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM export_receipt WHERE workspace_id = ? AND document_id = ? "
                                + "ORDER BY id DESC LIMIT 1",
                        this::mapReceipt,
                        workspaceId,
                        documentId)
                .stream()
                .findFirst();
    }

    private ExportReceipt mapReceipt(ResultSet rs, int rowNum) throws SQLException {
        Long pdfArtifactId = (Long) rs.getObject("pdf_artifact_id");
        return new ExportReceipt(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getLong("revision_id"),
                rs.getLong("template_version_id"),
                rs.getLong("export_approval_id"),
                rs.getLong("validation_manifest_id"),
                rs.getLong("docx_artifact_id"),
                rs.getString("docx_sha256"),
                pdfArtifactId,
                rs.getString("pdf_sha256"),
                ExportFormat.valueOf(rs.getString("format")),
                rs.getLong("actor_user_id"),
                rs.getObject("exported_at", OffsetDateTime.class));
    }
}
