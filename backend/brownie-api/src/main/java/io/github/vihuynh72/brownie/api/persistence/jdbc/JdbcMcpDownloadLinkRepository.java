package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.mcp.ClaimedMcpDownload;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadFile;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadLink;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadLinkRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC persistence for download links. {@link #claim} is the anonymous
 * route's only database touch: it goes through the definer-rights
 * function that checks the digest and stamps the link used in one
 * statement, so the route never takes a member context it does not have.
 */
@Repository
class JdbcMcpDownloadLinkRepository implements McpDownloadLinkRepository {

    private static final String COLUMNS =
            "id, workspace_id, grant_id, export_receipt_id, file, expires_at, used_at, created_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcMcpDownloadLinkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public McpDownloadLink create(
            long workspaceId,
            long userId,
            long grantId,
            long exportReceiptId,
            McpDownloadFile file,
            String tokenSha256,
            OffsetDateTime expiresAt) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO mcp_download_link
                    (workspace_id, grant_id, export_receipt_id, file, token_sha256, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING \
                """ + COLUMNS,
                this::mapLink,
                workspaceId,
                grantId,
                exportReceiptId,
                file.name(),
                tokenSha256,
                expiresAt);
    }

    @Override
    @Transactional
    public Optional<ClaimedMcpDownload> claim(String tokenSha256) {
        return jdbcTemplate.query(
                        "SELECT workspace_id, link_id, export_receipt_id, file, user_id FROM mcp_claim_download_link(?)",
                        (rs, rowNum) -> new ClaimedMcpDownload(
                                rs.getLong("workspace_id"),
                                rs.getLong("link_id"),
                                rs.getLong("export_receipt_id"),
                                McpDownloadFile.valueOf(rs.getString("file")),
                                rs.getLong("user_id")),
                        tokenSha256)
                .stream()
                .findFirst();
    }

    private McpDownloadLink mapLink(ResultSet rs, int rowNum) throws SQLException {
        return new McpDownloadLink(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("grant_id"),
                rs.getLong("export_receipt_id"),
                McpDownloadFile.valueOf(rs.getString("file")),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("used_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
