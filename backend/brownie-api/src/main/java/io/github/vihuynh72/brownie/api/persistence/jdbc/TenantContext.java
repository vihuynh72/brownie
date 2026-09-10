package io.github.vihuynh72.brownie.api.persistence.jdbc;

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
}
