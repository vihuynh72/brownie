package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.job.JobOutputArtifactRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Reads {@code job_output_artifact}, already RLS-protected for {@code brownie_api} the same way every other tenant table is. */
@Repository
class JdbcJobOutputArtifactRepository implements JobOutputArtifactRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcJobOutputArtifactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findArtifactId(long workspaceId, long userId, long jobId, String outputKind) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT artifact_id FROM job_output_artifact WHERE workspace_id = ? AND job_id = ? AND output_kind = ?",
                        (rs, rowNum) -> rs.getLong("artifact_id"),
                        workspaceId,
                        jobId,
                        outputKind)
                .stream()
                .findFirst();
    }
}
