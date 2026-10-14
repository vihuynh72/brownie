package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.mcp.McpToolCall;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallOutcome;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence for the append-only MCP tool-call record. */
@Repository
class JdbcMcpToolCallRepository implements McpToolCallRepository {

    private static final String COLUMNS = "id, grant_id, document_id, tool, outcome, code, at";

    private final JdbcTemplate jdbcTemplate;

    JdbcMcpToolCallRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public McpToolCall record(long workspaceId, long userId, long grantId, Long documentId, String tool, McpToolCallOutcome outcome, String code) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO mcp_tool_call (workspace_id, grant_id, document_id, tool, outcome, code)
                VALUES (?, ?, (SELECT id FROM document WHERE workspace_id = ? AND id = ?), ?, ?, ?)
                RETURNING \
                """ + COLUMNS,
                this::mapCall,
                workspaceId,
                grantId,
                workspaceId,
                documentId,
                tool,
                outcome.name(),
                code);
    }

    @Override
    @Transactional
    public List<McpToolCall> findForDocument(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM mcp_tool_call WHERE workspace_id = ? AND document_id = ? ORDER BY at, id",
                this::mapCall,
                workspaceId,
                documentId);
    }

    private McpToolCall mapCall(ResultSet rs, int rowNum) throws SQLException {
        long documentId = rs.getLong("document_id");
        return new McpToolCall(
                rs.getLong("id"),
                rs.getLong("grant_id"),
                rs.wasNull() ? null : documentId,
                rs.getString("tool"),
                McpToolCallOutcome.valueOf(rs.getString("outcome")),
                rs.getString("code"),
                rs.getObject("at", OffsetDateTime.class));
    }
}
