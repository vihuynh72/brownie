package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.mcp.McpAppliedRunRepository;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence for the run-to-proposal record. */
@Repository
class JdbcMcpAppliedRunRepository implements McpAppliedRunRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcMcpAppliedRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findProposal(long workspaceId, long userId, long runId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT proposal_id FROM mcp_applied_run WHERE workspace_id = ? AND run_id = ?",
                        (rs, rowNum) -> rs.getLong("proposal_id"),
                        workspaceId,
                        runId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public void record(long workspaceId, long userId, long runId, long documentId, long proposalId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO mcp_applied_run (workspace_id, run_id, document_id, proposal_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (workspace_id, run_id) DO NOTHING
                """,
                workspaceId,
                runId,
                documentId,
                proposalId);
    }
}
