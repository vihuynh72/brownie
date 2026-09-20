package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.generation.GenerationRun;
import io.github.vihuynh72.brownie.core.generation.GenerationRunRepository;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code record} is insert-or-return-existing under the table's own {@code
 * UNIQUE (job_id)}: a start request replayed under the same idempotency
 * key resolves to the same job, so both callers converge on the one run
 * that job deserves. Every read joins the run's source snapshot so the
 * artifact behind it travels with the run.
 */
@Repository
class JdbcGenerationRunRepository implements GenerationRunRepository {

    private static final String SELECT = """
            SELECT r.id, r.workspace_id, r.document_id, r.base_revision_id, r.template_version_id, r.source_snapshot_id,
                   s.artifact_id AS source_artifact_id, r.job_id, r.bundle_hash, r.model_name, r.prompt_version,
                   r.requested_by_user_id, r.created_at
            FROM generation_run r
            JOIN source_snapshot s ON s.workspace_id = r.workspace_id AND s.id = r.source_snapshot_id
            """;

    private final JdbcTemplate jdbcTemplate;

    JdbcGenerationRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public GenerationRun record(long workspaceId, long userId, NewGenerationRun run) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // Takes the document's row lock and answers nothing for a document
        // in the trash. Moving a document to the trash needs that same lock,
        // so the two are serialized: either the trash sees this run's job
        // and cancels it, or this sees the trash and the caller's whole
        // transaction, job included, is rolled back.
        boolean live = !jdbcTemplate
                .queryForList("SELECT document_id FROM lock_document_current_revision(?, ?)", Long.class, workspaceId, run.documentId())
                .isEmpty();
        if (!live) {
            throw new DocumentNotFoundException(run.documentId());
        }
        jdbcTemplate.update(
                """
                INSERT INTO generation_run (
                    workspace_id, document_id, base_revision_id, template_version_id, source_snapshot_id, job_id,
                    bundle_hash, model_name, prompt_version, requested_by_user_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_id) DO NOTHING
                """,
                workspaceId,
                run.documentId(),
                run.baseRevisionId(),
                run.templateVersionId(),
                run.sourceSnapshotId(),
                run.jobId(),
                run.bundleHash(),
                run.modelName(),
                run.promptVersion(),
                userId);
        return findByJob(workspaceId, userId, run.jobId())
                .orElseThrow(() -> new IllegalStateException("Generation run for job " + run.jobId() + " vanished after saving it."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GenerationRun> findByJob(long workspaceId, long userId, long jobId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(SELECT + " WHERE r.workspace_id = ? AND r.job_id = ?", this::mapRow, workspaceId, jobId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GenerationRun> findForDocument(long workspaceId, long userId, long documentId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                SELECT + " WHERE r.workspace_id = ? AND r.document_id = ? ORDER BY r.created_at DESC, r.id DESC",
                this::mapRow,
                workspaceId,
                documentId);
    }

    private GenerationRun mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new GenerationRun(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("document_id"),
                rs.getLong("base_revision_id"),
                rs.getLong("template_version_id"),
                rs.getLong("source_snapshot_id"),
                rs.getLong("source_artifact_id"),
                rs.getLong("job_id"),
                rs.getString("bundle_hash"),
                rs.getString("model_name"),
                rs.getString("prompt_version"),
                rs.getLong("requested_by_user_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
