package io.github.vihuynh72.brownie.worker.persistence;

import io.github.vihuynh72.brownie.core.retention.DeletionBlobTask;
import io.github.vihuynh72.brownie.core.retention.DeletionSweepRepository;
import io.github.vihuynh72.brownie.core.retention.ExpiredTrashResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Executes the worker-only deletion routines without table access. The
 * worker login can read neither the ledger nor anything a deletion removes;
 * what comes back from these routines is request ids and opaque object
 * keys, which is all the sweep needs.
 */
@Repository
class JdbcDeletionSweepRepository implements DeletionSweepRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    JdbcDeletionSweepRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = Objects.requireNonNull(jdbcTemplateProvider, "jdbcTemplateProvider must not be null");
    }

    @Override
    @Transactional
    public List<ExpiredTrashResult> purgeExpiredTrash(int limit) {
        requireBatchSize(limit, 256);
        return jdbc().query(
                "SELECT request_id, outcome FROM public.worker_purge_expired_trash(?)",
                (rs, rowNum) -> new ExpiredTrashResult(rs.getLong("request_id"), rs.getString("outcome")),
                limit);
    }

    @Override
    @Transactional
    public List<DeletionBlobTask> collectPendingBlobDeletions(int limit) {
        requireBatchSize(limit, 512);
        return jdbc().query(
                "SELECT id, workspace_id, deletion_request_id, object_key, attempt_count"
                        + " FROM public.worker_collect_pending_blob_deletions(?)",
                (rs, rowNum) -> new DeletionBlobTask(
                        rs.getLong("id"),
                        rs.getLong("workspace_id"),
                        rs.getLong("deletion_request_id"),
                        rs.getString("object_key"),
                        rs.getInt("attempt_count")),
                limit);
    }

    @Override
    @Transactional
    public boolean markBlobDeleted(long taskId, String objectKey) {
        if (taskId < 1) {
            throw new IllegalArgumentException("taskId must be positive.");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank.");
        }
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_mark_blob_deleted(?, ?)", Boolean.class, taskId, objectKey));
    }

    @Override
    @Transactional
    public List<Long> collectUnverifiedDeletions(int limit) {
        requireBatchSize(limit, 256);
        return jdbc().queryForList("SELECT * FROM public.worker_collect_unverified_deletions(?)", Long.class, limit);
    }

    @Override
    @Transactional
    public boolean verifyDeletion(long requestId) {
        if (requestId < 1) {
            throw new IllegalArgumentException("requestId must be positive.");
        }
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_verify_deletion(?)", Boolean.class, requestId));
    }

    private static void requireBatchSize(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw new IllegalArgumentException("Batch size must be between one and " + max + ".");
        }
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate template = jdbcTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("A database-backed worker operation requires a configured datasource.");
        }
        return template;
    }
}
