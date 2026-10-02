package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.template.FillSpotReviewRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A review is written once; keeping a field again is a conflict that changes nothing, so no row is ever updated. */
@Repository
class JdbcFillSpotReviewRepository implements FillSpotReviewRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcFillSpotReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void keep(long workspaceId, long userId, long templateId, Collection<String> fieldIds) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        List<Object[]> rows = fieldIds.stream().sorted().map(fieldId -> new Object[] {workspaceId, templateId, fieldId, userId}).toList();
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO fill_spot_review (workspace_id, template_id, field_id, decision, reviewed_by_user_id)
                VALUES (?, ?, ?, 'KEPT', ?)
                ON CONFLICT (template_id, field_id) DO NOTHING
                """,
                rows);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> keptFieldIds(long workspaceId, long userId, long templateId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT field_id FROM fill_spot_review WHERE workspace_id = ? AND template_id = ? AND decision = 'KEPT' ORDER BY field_id",
                String.class, workspaceId, templateId));
    }
}
