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

import java.time.OffsetDateTime;
import java.util.List;

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
    public Workspace ensurePersonalWorkspace(long ownerUserId) {
        // The partial unique index on (owner_user_id) WHERE is_personal is the
        // actual conflict target here: two concurrent logins for the same new
        // user race safely, since only one INSERT can ever win, and the
        // following SELECT reads back whichever row that was.
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
    public List<WorkspaceMember> findMembershipsForUser(long userId) {
        return jdbcTemplate.query(
                "SELECT workspace_id, user_id, role, state, created_at FROM workspace_member WHERE user_id = ?",
                MEMBER_ROW_MAPPER,
                userId);
    }
}
