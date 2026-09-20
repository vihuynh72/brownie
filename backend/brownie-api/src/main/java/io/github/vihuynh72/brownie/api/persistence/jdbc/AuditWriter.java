package io.github.vihuynh72.brownie.api.persistence.jdbc;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes one audit row in the caller's own transaction, so the row exists
 * exactly when the action it describes does. The table's insert policy
 * accepts a row only in the acting person's own name, in a workspace that
 * person belongs to, which is why the caller must already have set the
 * tenant context. {@code detailsJson} is a small object of ids, counts and
 * codes; never a title, a filename or anything a person wrote.
 */
final class AuditWriter {

    private AuditWriter() {
    }

    static void append(
            JdbcTemplate jdbcTemplate, long workspaceId, long actorUserId, String action, String resourceType, long resourceId,
            String detailsJson) {
        String correlationId = MDC.get("correlationId");
        jdbcTemplate.update(
                """
                INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id, correlation_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                workspaceId,
                actorUserId,
                action,
                resourceType,
                resourceId,
                correlationId == null || correlationId.isBlank() ? null : truncate(correlationId),
                detailsJson);
    }

    private static String truncate(String correlationId) {
        return correlationId.length() > 128 ? correlationId.substring(0, 128) : correlationId;
    }
}
