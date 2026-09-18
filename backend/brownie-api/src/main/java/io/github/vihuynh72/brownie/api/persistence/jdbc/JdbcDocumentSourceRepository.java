package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.source.DocumentSource;
import io.github.vihuynh72.brownie.core.source.DocumentSourceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code link} is insert-or-return-existing under the table's own primary
 * key: two callers attaching the same snapshot to the same document
 * concurrently both converge on the one link, the same reasoning {@code
 * JdbcSourceSnapshotRepository} already applies to the snapshot itself.
 */
@Repository
class JdbcDocumentSourceRepository implements DocumentSourceRepository {

    private static final String COLUMNS = "workspace_id, document_id, source_snapshot_id, attached_by_user_id, attached_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcDocumentSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public DocumentSource link(long workspaceId, long userId, long documentId, long sourceSnapshotId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO document_source (workspace_id, document_id, source_snapshot_id, attached_by_user_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (workspace_id, document_id, source_snapshot_id) DO NOTHING
                """,
                workspaceId,
                documentId,
                sourceSnapshotId,
                userId);
        return find(workspaceId, userId, documentId, sourceSnapshotId)
                .orElseThrow(() -> new IllegalStateException(
                        "Document " + documentId + "'s link to source snapshot " + sourceSnapshotId + " vanished after saving it."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentSource> find(long workspaceId, long userId, long documentId, long sourceSnapshotId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + COLUMNS + " FROM document_source WHERE workspace_id = ? AND document_id = ? AND source_snapshot_id = ?",
                        this::mapRow,
                        workspaceId,
                        documentId,
                        sourceSnapshotId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentSource> findForDocument(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM document_source WHERE workspace_id = ? AND document_id = ?"
                        + " ORDER BY attached_at DESC, source_snapshot_id DESC",
                this::mapRow,
                workspaceId,
                documentId);
    }

    private DocumentSource mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentSource(
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getLong("source_snapshot_id"),
                rs.getLong("attached_by_user_id"),
                rs.getObject("attached_at", OffsetDateTime.class));
    }
}
