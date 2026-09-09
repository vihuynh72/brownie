package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMember;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceMemberState;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRole;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
class JdbcWorkspaceRepository implements WorkspaceRepository {

    private static final RowMapper<Workspace> WORKSPACE_ROW_MAPPER = (rs, rowNum) -> new Workspace(
            rs.getLong("id"),
            rs.getLong("owner_user_id"),
            rs.getString("locale"),
            rs.getString("time_zone"),
            WorkspaceStatus.valueOf(rs.getString("status")),
            rs.getInt("retention_policy_version"),
            rs.getString("quota_policy"),
            rs.getBoolean("is_personal"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<WorkspaceMember> MEMBER_ROW_MAPPER = (rs, rowNum) -> new WorkspaceMember(
            rs.getLong("workspace_id"),
            rs.getLong("user_id"),
            WorkspaceRole.valueOf(rs.getString("role")),
            WorkspaceMemberState.valueOf(rs.getString("state")),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    JdbcWorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public Workspace ensurePersonalWorkspace(long ownerUserId) {
        setCurrentUser(ownerUserId);
        // The partial unique index on (owner_user_id) WHERE is_personal is the
        // actual conflict target here: two concurrent logins for the same new
        // user race safely, since only one INSERT can ever win, and the
        // following SELECT reads back whichever row that was -- visible
        // through the row-level security policy's ownership clause even
        // before the membership row two lines down exists.
        jdbcTemplate.update(
                """
                INSERT INTO workspace (owner_user_id, is_personal)
                VALUES (?, true)
                ON CONFLICT (owner_user_id) WHERE is_personal DO NOTHING
                """,
                ownerUserId);
        Workspace workspace = jdbcTemplate.queryForObject(
                """
                SELECT id, owner_user_id, locale, time_zone, status, retention_policy_version, quota_policy, is_personal, created_at
                FROM workspace WHERE owner_user_id = ? AND is_personal
                """,
                WORKSPACE_ROW_MAPPER,
                ownerUserId);
        jdbcTemplate.update(
                """
                INSERT INTO workspace_member (workspace_id, user_id, role, state)
                VALUES (?, ?, 'OWNER', 'ACTIVE')
                ON CONFLICT (workspace_id, user_id) DO NOTHING
                """,
                workspace.id(),
                ownerUserId);
        return workspace;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceMember> findMembershipsForUser(long userId) {
        setCurrentUser(userId);
        return jdbcTemplate.query(
                "SELECT workspace_id, user_id, role, state, created_at FROM workspace_member WHERE user_id = ?",
                MEMBER_ROW_MAPPER,
                userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspaceRole> findRole(long workspaceId, long userId) {
        setCurrentUser(userId);
        List<String> roles = jdbcTemplate.query(
                "SELECT role FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                (rs, rowNum) -> rs.getString("role"),
                workspaceId,
                userId);
        return roles.stream().findFirst().map(WorkspaceRole::valueOf);
    }

    /**
     * The row-level security policies on workspace/workspace_member read
     * this value back via current_setting('app.current_user_id', ...).
     * is_local=true (set_config's third argument) is what makes it
     * transaction-local: Postgres clears it automatically the instant this
     * transaction ends, so it can never leak into whatever request reuses
     * this pooled connection next. Must run as a query, not an update --
     * the Postgres driver rejects executeUpdate() on any statement that
     * returns a result set, which SELECT always does.
     */
    private void setCurrentUser(long userId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)", String.class, String.valueOf(userId));
    }
}
