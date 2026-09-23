package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.connector.ConnectionNotFoundException;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.GrantRevocationReason;
import io.github.vihuynh72.brownie.core.connector.ResourceGrant;
import io.github.vihuynh72.brownie.core.connector.ResourceGrantRepository;
import io.github.vihuynh72.brownie.core.connector.ResourceGrantType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The table's policies decide who: a grant is recorded only through the
 * person's own usable connection, is visible only to them, and can only be
 * revoked. Its constraint decides what: reading, and nothing else.
 */
@Repository
class JdbcResourceGrantRepository implements ResourceGrantRepository {

    private static final String COLUMNS =
            "id, workspace_id, connection_id, resource_type, external_id, display_name, granted_at, revoked_at, revoked_reason";

    private final JdbcTemplate jdbcTemplate;

    JdbcResourceGrantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public ResourceGrant grant(
            long workspaceId, long userId, long connectionId, ResourceGrantType type, String externalId, String displayName) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // Choosing the same thing twice records one choice: the second finds the first.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('connector_resource_grant:' || ?::text || ':' || ?, 0))",
                rs -> null,
                connectionId,
                externalId);
        // Held until this transaction ends, so a disconnect cannot finish in between and leave this choice open behind it.
        boolean active = !jdbcTemplate
                .queryForList(
                        "SELECT id FROM connector_connection WHERE workspace_id = ? AND id = ? AND state = 'ACTIVE' FOR SHARE",
                        Long.class,
                        workspaceId,
                        connectionId)
                .isEmpty();
        if (!active) {
            throw new ConnectionNotFoundException(switch (type) {
                case DRIVE_FILE -> ConnectorAccess.DRIVE_FILES;
                case CALENDAR -> ConnectorAccess.CALENDAR_EVENTS;
            });
        }
        Optional<ResourceGrant> open = jdbcTemplate
                .query(
                        "SELECT " + COLUMNS + " FROM connector_resource_grant"
                                + " WHERE workspace_id = ? AND connection_id = ? AND resource_type = ? AND external_id = ? AND revoked_at IS NULL",
                        this::mapGrant,
                        workspaceId,
                        connectionId,
                        type.name(),
                        externalId)
                .stream()
                .findFirst();
        if (open.isPresent()) {
            return open.get();
        }
        return jdbcTemplate.queryForObject(
                "INSERT INTO connector_resource_grant (workspace_id, connection_id, resource_type, external_id, display_name, granted_by_user_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                this::mapGrant,
                workspaceId,
                connectionId,
                type.name(),
                externalId,
                displayName,
                userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceGrant> findOpen(long workspaceId, long userId, long connectionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM connector_resource_grant"
                        + " WHERE workspace_id = ? AND connection_id = ? AND revoked_at IS NULL ORDER BY granted_at DESC, id DESC",
                this::mapGrant,
                workspaceId,
                connectionId);
    }

    private ResourceGrant mapGrant(ResultSet rs, int rowNum) throws SQLException {
        String reason = rs.getString("revoked_reason");
        return new ResourceGrant(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("connection_id"),
                ResourceGrantType.valueOf(rs.getString("resource_type")),
                rs.getString("external_id"),
                rs.getString("display_name"),
                rs.getObject("granted_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class),
                reason == null ? null : GrantRevocationReason.valueOf(reason));
    }
}
