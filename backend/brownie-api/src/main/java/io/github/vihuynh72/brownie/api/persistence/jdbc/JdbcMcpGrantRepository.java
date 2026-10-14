package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.mcp.McpGrant;
import io.github.vihuynh72.brownie.core.mcp.McpGrantKind;
import io.github.vihuynh72.brownie.core.mcp.McpGrantRepository;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.mcp.ResolvedMcpGrant;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC persistence for MCP grants. Member-facing methods run under the
 * same row-level member policies as every other tenant table;
 * {@link #resolvePersonalToken} is the one exception -- it calls the
 * definer-rights function, because the token lookup is what establishes
 * the caller's identity and so cannot itself need a member context.
 */
@Repository
class JdbcMcpGrantRepository implements McpGrantRepository {

    private static final String COLUMNS =
            "id, workspace_id, user_id, kind, name, registered_client_id, scopes, token_hint, created_at, last_used_at, revoked_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcMcpGrantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public McpGrant create(
            long workspaceId,
            long userId,
            McpGrantKind kind,
            String name,
            String registeredClientId,
            Set<McpScope> scopes,
            String tokenSha256,
            String tokenHint) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        McpGrant grant = jdbcTemplate.queryForObject(
                """
                INSERT INTO mcp_grant
                    (workspace_id, user_id, kind, name, registered_client_id, scopes, token_sha256, token_hint)
                VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?)
                RETURNING \
                """ + COLUMNS,
                this::mapGrant,
                workspaceId,
                userId,
                kind.name(),
                name,
                registeredClientId,
                scopesArray(scopes),
                tokenSha256,
                tokenHint);
        // The kind names the credential family, not its label: a person's
        // own name for their token is user text and stays out of the audit
        // record.
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "MCP_GRANT_CREATED", "mcp-grant", grant.id(),
                "{\"kind\":\"" + kind.name() + "\"}");
        return grant;
    }

    @Override
    @Transactional
    public Optional<ResolvedMcpGrant> resolvePersonalToken(String tokenSha256) {
        return jdbcTemplate.query(
                        "SELECT workspace_id, grant_id, user_id, kind, name, scopes FROM mcp_resolve_personal_token(?)",
                        (rs, rowNum) -> new ResolvedMcpGrant(
                                rs.getLong("workspace_id"),
                                rs.getLong("grant_id"),
                                rs.getLong("user_id"),
                                McpGrantKind.valueOf(rs.getString("kind")),
                                rs.getString("name"),
                                toScopes(rs.getArray("scopes"))),
                        tokenSha256)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public List<McpGrant> findAllForUser(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM mcp_grant WHERE workspace_id = ? AND user_id = ? ORDER BY id DESC",
                this::mapGrant,
                workspaceId,
                userId);
    }

    @Override
    @Transactional
    public Optional<McpGrant> revoke(long workspaceId, long userId, long grantId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // Any live grant in this workspace, not only the revoking member's
        // own: managing the workspace includes retiring a token whose owner
        // cannot be asked.
        Optional<McpGrant> revoked = jdbcTemplate.query(
                        "UPDATE mcp_grant SET revoked_at = now() WHERE workspace_id = ? AND id = ? AND revoked_at IS NULL RETURNING " + COLUMNS,
                        this::mapGrant,
                        workspaceId,
                        grantId)
                .stream()
                .findFirst();
        revoked.ifPresent(grant -> AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "MCP_GRANT_REVOKED", "mcp-grant", grant.id(), "{}"));
        return revoked;
    }

    private McpGrant mapGrant(ResultSet rs, int rowNum) throws SQLException {
        return new McpGrant(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("user_id"),
                McpGrantKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("registered_client_id"),
                toScopes(rs.getArray("scopes")),
                rs.getString("token_hint"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("last_used_at", java.time.OffsetDateTime.class),
                rs.getObject("revoked_at", java.time.OffsetDateTime.class));
    }

    private Array scopesArray(Set<McpScope> scopes) {
        String[] tokens = scopes.stream().map(McpScope::token).sorted().toArray(String[]::new);
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Array>) connection -> connection.createArrayOf("text", tokens));
    }

    private static Set<McpScope> toScopes(Array array) throws SQLException {
        String[] tokens = (String[]) array.getArray();
        Set<McpScope> scopes = Arrays.stream(tokens)
                .map(token -> McpScope.parse(token).orElseThrow(() -> new IllegalStateException("Unknown MCP scope " + token)))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(McpScope.class)));
        return scopes;
    }
}
