package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.support.SupportGrant;
import io.github.vihuynh72.brownie.core.support.SupportGrantRepository;
import io.github.vihuynh72.brownie.core.support.SupportGrantScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The table's own policies do the enforcing: only the workspace's owner may
 * insert a grant, only in their own name; the only update an open grant
 * accepts is its revocation; nothing is ever deleted. Each write records
 * its audit row in the same transaction.
 */
@Repository
class JdbcSupportGrantRepository implements SupportGrantRepository {

    private static final String COLUMNS = "id, workspace_id, granted_by_user_id, scope, granted_at, expires_at, revoked_at";

    private final JdbcTemplate jdbcTemplate;

    JdbcSupportGrantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Optional<SupportGrant> create(long workspaceId, long userId, SupportGrantScope scope, int days) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // One grant decision per workspace at a time, so "is one already open" and "open one" cannot interleave.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('support_grant:' || ?::text, 0))", rs -> null, workspaceId);
        Optional<SupportGrant> created = jdbcTemplate
                .query(
                        """
                        INSERT INTO support_grant (workspace_id, granted_by_user_id, scope, expires_at)
                        SELECT ?, ?, ?, now() + make_interval(days => ?)
                        WHERE NOT EXISTS (
                            SELECT 1 FROM support_grant g
                            WHERE g.workspace_id = ? AND g.scope = ? AND g.revoked_at IS NULL AND g.expires_at > clock_timestamp())
                        RETURNING
                        """ + " " + COLUMNS,
                        this::mapGrant,
                        workspaceId,
                        userId,
                        scope.name(),
                        days,
                        workspaceId,
                        scope.name())
                .stream()
                .findFirst();
        created.ifPresent(grant -> AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "SUPPORT_GRANT_CREATED", "support-grant", grant.id(),
                "{\"scope\":\"" + scope.name() + "\",\"days\":" + days + "}"));
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupportGrant> findAll(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM support_grant WHERE workspace_id = ? ORDER BY granted_at DESC, id DESC",
                this::mapGrant,
                workspaceId);
    }

    @Override
    @Transactional
    public Optional<SupportGrant> revoke(long workspaceId, long userId, long grantId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Optional<SupportGrant> revoked = jdbcTemplate
                .query(
                        "UPDATE support_grant SET revoked_at = now(), revoked_by_user_id = ?"
                                + " WHERE workspace_id = ? AND id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp()"
                                + " RETURNING " + COLUMNS,
                        this::mapGrant,
                        userId,
                        workspaceId,
                        grantId)
                .stream()
                .findFirst();
        revoked.ifPresent(grant -> AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "SUPPORT_GRANT_REVOKED", "support-grant", grant.id(),
                "{\"scope\":\"" + grant.scope().name() + "\"}"));
        return revoked;
    }

    private SupportGrant mapGrant(ResultSet rs, int rowNum) throws SQLException {
        return new SupportGrant(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("granted_by_user_id"),
                SupportGrantScope.valueOf(rs.getString("scope")),
                rs.getObject("granted_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class));
    }
}
