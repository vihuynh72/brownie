package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.retention.DeletionRepository;
import io.github.vihuynh72.brownie.core.retention.DeletionRequest;
import io.github.vihuynh72.brownie.core.retention.DeletionScope;
import io.github.vihuynh72.brownie.core.retention.DeletionState;
import io.github.vihuynh72.brownie.core.retention.PurgeOutcome;
import io.github.vihuynh72.brownie.core.retention.RestoreOutcome;
import io.github.vihuynh72.brownie.core.retention.WorkspaceDeletion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads the deletion ledger under the member's own row-level security
 * context and writes it only through the tenant-checked database routines:
 * this role holds no INSERT, UPDATE or DELETE on either ledger table, and
 * no DELETE policy exists on any table a deletion removes rows from. Each
 * routine checks the acting person's membership itself, from the same
 * transaction-local setting every policy reads, so a routine called for a
 * workspace the actor does not belong to answers exactly as it does for a
 * target that does not exist.
 */
@Repository
class JdbcDeletionRepository implements DeletionRepository {

    /**
     * The title is joined in only while the request is still an open trash
     * entry, which is the only time the document row exists; the ledger
     * itself never stores it.
     */
    private static final String SELECT_REQUEST =
            """
            SELECT r.id, r.workspace_id, r.scope, r.target_id, r.state, r.requested_by_user_id, r.requested_at,
                   r.purge_after, r.restored_at, r.purged_at, r.verified_at,
                   (SELECT count(*) FROM deletion_blob_task t
                     WHERE t.workspace_id = r.workspace_id
                       AND t.deletion_request_id = r.id
                       AND t.state = 'PENDING') AS pending_object_count,
                   d.title AS target_title
            FROM deletion_request r
            LEFT JOIN document d
                ON r.scope = 'DOCUMENT'
                AND r.state = 'TRASHED'
                AND d.workspace_id = r.workspace_id
                AND d.id = r.target_id
            """;

    private final JdbcTemplate jdbcTemplate;

    JdbcDeletionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Optional<Long> trashDocument(long workspaceId, long userId, long documentId, int retentionDays) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        Long requestId = jdbcTemplate.queryForObject(
                "SELECT trash_document(?, ?, ?)", Long.class, workspaceId, documentId, retentionDays);
        return Optional.ofNullable(requestId);
    }

    @Override
    @Transactional
    public RestoreOutcome restoreDocument(long workspaceId, long userId, long requestId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        String outcome = jdbcTemplate.queryForObject(
                "SELECT restore_trashed_document(?, ?)", String.class, workspaceId, requestId);
        return RestoreOutcome.valueOf(outcome);
    }

    @Override
    @Transactional
    public PurgeOutcome purgeDocument(long workspaceId, long userId, long requestId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        String outcome = jdbcTemplate.queryForObject(
                "SELECT purge_trashed_document(?, ?)", String.class, workspaceId, requestId);
        return PurgeOutcome.valueOf(outcome);
    }

    @Override
    @Transactional
    public WorkspaceDeletion deleteWorkspace(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        TenantContext.setCorrelationId(jdbcTemplate);
        return jdbcTemplate.queryForObject(
                "SELECT outcome, request_id FROM delete_workspace(?)",
                (rs, rowNum) -> new WorkspaceDeletion(
                        PurgeOutcome.valueOf(rs.getString("outcome")), (Long) rs.getObject("request_id")),
                workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DeletionRequest> find(long workspaceId, long userId, long requestId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(SELECT_REQUEST + " WHERE r.workspace_id = ? AND r.id = ?", this::mapRequest, workspaceId, requestId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeletionRequest> findAll(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                SELECT_REQUEST + " WHERE r.workspace_id = ? ORDER BY r.requested_at DESC, r.id DESC",
                this::mapRequest,
                workspaceId);
    }

    private DeletionRequest mapRequest(ResultSet rs, int rowNum) throws SQLException {
        return new DeletionRequest(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                DeletionScope.valueOf(rs.getString("scope")),
                rs.getLong("target_id"),
                DeletionState.valueOf(rs.getString("state")),
                rs.getLong("requested_by_user_id"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("purge_after", OffsetDateTime.class),
                rs.getObject("restored_at", OffsetDateTime.class),
                rs.getObject("purged_at", OffsetDateTime.class),
                rs.getObject("verified_at", OffsetDateTime.class),
                rs.getInt("pending_object_count"),
                rs.getString("target_title"));
    }
}
