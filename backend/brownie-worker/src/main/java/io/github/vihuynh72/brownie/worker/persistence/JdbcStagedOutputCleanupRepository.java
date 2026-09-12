package io.github.vihuynh72.brownie.worker.persistence;

import io.github.vihuynh72.brownie.core.job.StagedOutput;
import io.github.vihuynh72.brownie.core.job.StagedOutputCleanupRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Executes the small worker-only cleanup routines without table access. */
@Repository
class JdbcStagedOutputCleanupRepository implements StagedOutputCleanupRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    JdbcStagedOutputCleanupRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = Objects.requireNonNull(jdbcTemplateProvider, "jdbcTemplateProvider must not be null");
    }

    @Override
    @Transactional
    public List<StagedOutput> collectDiscardedOutputs(int limit) {
        if (limit < 1 || limit > 512) {
            throw new IllegalArgumentException("Cleanup batch size must be between one and 512.");
        }
        return jdbc().query(
                "SELECT * FROM public.worker_collect_discarded_staged_outputs(?)",
                JdbcJobLeaseRepository::mapStagedOutput,
                limit);
    }

    @Override
    @Transactional
    public boolean markDiscardedOutputCleaned(long stagedOutputId, String objectKey) {
        if (stagedOutputId < 1) {
            throw new IllegalArgumentException("stagedOutputId must be positive.");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank.");
        }
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_mark_discarded_staged_output_cleaned(?, ?)",
                Boolean.class,
                stagedOutputId,
                objectKey));
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate template = jdbcTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("A database-backed worker operation requires a configured datasource.");
        }
        return template;
    }
}
