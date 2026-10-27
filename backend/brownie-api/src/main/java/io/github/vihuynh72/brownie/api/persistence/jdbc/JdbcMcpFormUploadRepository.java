package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.mcp.ClaimedFormUpload;
import io.github.vihuynh72.brownie.core.mcp.McpFormUpload;
import io.github.vihuynh72.brownie.core.mcp.McpFormUploadRepository;
import io.github.vihuynh72.brownie.core.mcp.PeekedFormUpload;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC persistence for upload links. {@link #claim} and {@link #peek} are
 * the anonymous route's reads and run without member context -- the
 * definer-side functions do it; {@link #create} and {@link
 * #findForWorkspace} run under the member's own context like every other
 * member-scoped row.
 */
@Repository
class JdbcMcpFormUploadRepository implements McpFormUploadRepository {

    private static final String COLUMNS =
            "id, workspace_id, user_id, grant_id, file_name, state, template_id, failure_code, created_at, expires_at, used_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcMcpFormUploadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public McpFormUpload create(long workspaceId, long userId, long grantId, String fileName, String tokenSha256) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                "INSERT INTO mcp_form_upload (workspace_id, user_id, grant_id, file_name, token_sha256)"
                        + " VALUES (?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                JdbcMcpFormUploadRepository::map,
                workspaceId, userId, grantId, fileName, tokenSha256);
    }

    @Override
    @Transactional
    public Optional<McpFormUpload> findForWorkspace(long workspaceId, long userId, long uploadId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM mcp_form_upload"
                                + " WHERE workspace_id = ? AND user_id = ? AND id = ?",
                        JdbcMcpFormUploadRepository::map,
                        workspaceId, userId, uploadId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ClaimedFormUpload> claim(String tokenSha256) {
        return jdbcTemplate.query(
                        "SELECT * FROM mcp_claim_form_upload(?)",
                        (rs, rowNum) -> new ClaimedFormUpload(
                                rs.getLong("workspace_id"),
                                rs.getLong("upload_id"),
                                rs.getLong("grant_id"),
                                rs.getLong("user_id"),
                                rs.getString("file_name"),
                                rs.getString("grant_name")),
                        tokenSha256)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<PeekedFormUpload> peek(String tokenSha256) {
        return jdbcTemplate.query(
                        "SELECT * FROM mcp_peek_form_upload(?)",
                        (rs, rowNum) -> new PeekedFormUpload(
                                rs.getLong("workspace_id"),
                                rs.getLong("upload_id"),
                                rs.getString("state"),
                                rs.getObject("expires_at", java.time.OffsetDateTime.class),
                                rs.getString("grant_name")),
                        tokenSha256)
                .stream()
                .findFirst();
    }

    @Override
    public void finish(long workspaceId, long uploadId, String state, Long templateId, String failureCode) {
        jdbcTemplate.queryForObject(
                "SELECT mcp_finish_form_upload(?, ?, ?, ?, ?)",
                Object.class, workspaceId, uploadId, state, templateId, failureCode);
    }

    private static McpFormUpload map(ResultSet rs, int rowNum) throws SQLException {
        long templateId = rs.getLong("template_id");
        return new McpFormUpload(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("user_id"),
                rs.getLong("grant_id"),
                rs.getString("file_name"),
                rs.getString("state"),
                rs.wasNull() ? null : templateId,
                rs.getString("failure_code"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("expires_at", java.time.OffsetDateTime.class),
                rs.getObject("used_at", java.time.OffsetDateTime.class));
    }
}
