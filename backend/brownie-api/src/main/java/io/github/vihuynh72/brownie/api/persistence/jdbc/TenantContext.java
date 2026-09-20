package io.github.vihuynh72.brownie.api.persistence.jdbc;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sets {@code app.current_user_id} for the current transaction, which
 * every row-level security policy in this schema reads back through
 * {@code current_workspace_user_id()}. Shared by every tenant-scoped JDBC
 * repository so this exact mechanism -- and the {@code is_local=true} /
 * empty-string-versus-NULL subtlety it depends on -- lives in exactly one
 * place rather than being copied per repository.
 */
final class TenantContext {

    private TenantContext() {
    }

    /**
     * Must run as a query, not an update -- the Postgres driver rejects
     * {@code executeUpdate()} on any statement that returns a result set,
     * which {@code SELECT set_config(...)} always does.
     */
    static void setCurrentUser(JdbcTemplate jdbcTemplate, long userId) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)", String.class, String.valueOf(userId));
    }

    /**
     * Names the request being served for the rest of this transaction, so
     * that a database routine which writes its own audit row can stamp it
     * with the same correlation id the response and the logs carry. Only
     * the repositories whose routines write audit rows call this: it is
     * one more round trip, and nothing else reads the setting. Absent
     * outside a request (a test calling a repository directly), in which
     * case the audit row simply has no correlation id.
     */
    static void setCorrelationId(JdbcTemplate jdbcTemplate) {
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            jdbcTemplate.queryForObject("SELECT set_config('app.correlation_id', ?, true)", String.class, correlationId);
        }
    }
}
