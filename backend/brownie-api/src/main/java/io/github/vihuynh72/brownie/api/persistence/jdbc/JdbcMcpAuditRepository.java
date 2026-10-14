package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.mcp.McpAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC audit writes for MCP-specific acts, the same shape every other repository's audit rows take. */
@Repository
class JdbcMcpAuditRepository implements McpAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcMcpAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void fillConfirmed(long workspaceId, long userId, long documentId, long proposalId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // Ids only: the audit record names what happened to which document, never what the fill said.
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "MCP_FILL_CONFIRMED", "document", documentId,
                "{\"proposalId\":" + proposalId + "}");
    }

    @Override
    @Transactional
    public void exported(long workspaceId, long userId, long documentId, long receiptId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "MCP_EXPORTED", "document", documentId,
                "{\"exportReceiptId\":" + receiptId + "}");
    }
}
