package io.github.vihuynh72.brownie.worker.persistence;

import io.github.vihuynh72.brownie.core.retention.ArtifactRetentionRepository;
import io.github.vihuynh72.brownie.core.retention.RemovablePayload;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Executes the worker-only file housekeeping routines without table access. */
@Repository
class JdbcArtifactRetentionRepository implements ArtifactRetentionRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    JdbcArtifactRetentionRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = Objects.requireNonNull(jdbcTemplateProvider, "jdbcTemplateProvider must not be null");
    }

    @Override
    @Transactional
    public int expireStaleUploads(Duration abandonedAfter, Duration stuckScanAfter, int limit) {
        requireBatchSize(limit);
        Integer changed = jdbc().queryForObject(
                "SELECT public.worker_expire_stale_uploads(?, ?, ?)",
                Integer.class,
                abandonedAfter.toMillis(),
                stuckScanAfter.toMillis(),
                limit);
        return changed == null ? 0 : changed;
    }

    @Override
    @Transactional
    public List<RemovablePayload> collectRemovablePayloads(Duration rejectedAfter, Duration unreferencedAfter, int limit) {
        requireBatchSize(limit);
        return jdbc().query(
                "SELECT artifact_id, workspace_id, blob_key FROM public.worker_collect_removable_payloads(?, ?, ?)",
                (rs, rowNum) -> new RemovablePayload(rs.getLong("artifact_id"), rs.getLong("workspace_id"), rs.getString("blob_key")),
                rejectedAfter.toMillis(),
                unreferencedAfter.toMillis(),
                limit);
    }

    @Override
    @Transactional
    public boolean markPayloadRemoved(long artifactId, String blobKey) {
        if (artifactId < 1) {
            throw new IllegalArgumentException("artifactId must be positive.");
        }
        if (blobKey == null || blobKey.isBlank()) {
            throw new IllegalArgumentException("blobKey must not be blank.");
        }
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_mark_payload_removed(?, ?)", Boolean.class, artifactId, blobKey));
    }

    private static void requireBatchSize(int limit) {
        if (limit < 1 || limit > 512) {
            throw new IllegalArgumentException("Batch size must be between one and 512.");
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
