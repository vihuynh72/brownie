package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.job.JobRetryOutcome;
import io.github.vihuynh72.brownie.core.job.JobRetryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Calls the one routine that may move a dead job back to the queue; this login has no UPDATE policy that reaches a job in that state. */
@Repository
class JdbcJobRetryRepository implements JobRetryRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcJobRetryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public JobRetryOutcome retry(long workspaceId, long actorUserId, long jobId) {
        TenantContext.setCurrentUser(jdbcTemplate, actorUserId);
        TenantContext.setCorrelationId(jdbcTemplate);
        return JobRetryOutcome.valueOf(
                jdbcTemplate.queryForObject("SELECT retry_dead_job(?, ?)", String.class, workspaceId, jobId));
    }
}
