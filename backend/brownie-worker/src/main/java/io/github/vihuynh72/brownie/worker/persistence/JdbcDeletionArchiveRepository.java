package io.github.vihuynh72.brownie.worker.persistence;

import io.github.vihuynh72.brownie.core.retention.ArchivedDeletion;
import io.github.vihuynh72.brownie.core.retention.DeletionArchiveRepository;
import io.github.vihuynh72.brownie.core.retention.DeletionScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Executes the worker-only routines that copy carried-out deletions out of
 * the database and apply copied-out ones to it. Like every worker
 * repository it has no table access: what comes back is ids, times and
 * counts, which is all an archived entry holds.
 */
@Repository
public class JdbcDeletionArchiveRepository implements DeletionArchiveRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    public JdbcDeletionArchiveRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = Objects.requireNonNull(jdbcTemplateProvider, "jdbcTemplateProvider must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArchivedDeletion> collectUnarchived(int limit) {
        if (limit < 1 || limit > 256) {
            throw new IllegalArgumentException("Batch size must be between one and 256.");
        }
        return jdbc().query(
                "SELECT request_id, workspace_id, scope, target_id, requested_by_user_id, requested_at, purged_at,"
                        + " target_created_at, inventory"
                        + " FROM public.worker_collect_unarchived_deletions(?)",
                (rs, rowNum) -> new ArchivedDeletion(
                        rs.getLong("request_id"),
                        rs.getLong("workspace_id"),
                        DeletionScope.valueOf(rs.getString("scope")),
                        rs.getLong("target_id"),
                        rs.getLong("requested_by_user_id"),
                        rs.getObject("requested_at", OffsetDateTime.class),
                        rs.getObject("purged_at", OffsetDateTime.class),
                        rs.getObject("target_created_at", OffsetDateTime.class),
                        rs.getString("inventory")),
                limit);
    }

    @Override
    @Transactional
    public boolean markArchived(long requestId) {
        return Boolean.TRUE.equals(
                jdbc().queryForObject("SELECT public.worker_mark_deletion_archived(?)", Boolean.class, requestId));
    }

    @Override
    @Transactional
    public String replay(ArchivedDeletion entry) {
        return jdbc().queryForObject(
                "SELECT public.worker_replay_deletion(?, ?, ?, ?, ?, ?)",
                String.class,
                entry.scope().name(),
                entry.workspaceId(),
                entry.targetId(),
                entry.requestedByUserId(),
                entry.requestedAt(),
                entry.targetCreatedAt());
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate template = jdbcTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("A database-backed worker operation requires a configured datasource.");
        }
        return template;
    }
}
