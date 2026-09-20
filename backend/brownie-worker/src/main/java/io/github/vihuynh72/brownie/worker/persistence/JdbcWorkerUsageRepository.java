package io.github.vihuynh72.brownie.worker.persistence;

import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageReservationOutcome;
import io.github.vihuynh72.brownie.core.job.JobLeaseToken;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Executes the worker-only usage routines without table access. The job
 * row, not this process, says which workspace and which person a request
 * is spent for; the worker contributes estimates, limits and its lease.
 */
@Repository
public class JdbcWorkerUsageRepository {

    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    JdbcWorkerUsageRepository(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplateProvider = Objects.requireNonNull(jdbcTemplateProvider, "jdbcTemplateProvider must not be null");
    }

    @Transactional
    public UsageReservationOutcome reserve(
            JobLeaseToken lease,
            String modelName,
            String promptVersion,
            String rateCard,
            int estimatedInputTokens,
            int estimatedMaxOutputTokens,
            BigDecimal estimatedCostUsd,
            UsageLimits runLimits,
            MonthlyUsageLimits monthlyLimits) {
        return jdbc().queryForObject(
                "SELECT outcome, usage_id FROM public.worker_reserve_model_usage(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (rs, rowNum) -> new UsageReservationOutcome(rs.getString("outcome"), (Long) rs.getObject("usage_id")),
                lease.jobId(),
                lease.workerId(),
                lease.fencingToken(),
                modelName,
                promptVersion,
                rateCard,
                estimatedInputTokens,
                estimatedMaxOutputTokens,
                estimatedCostUsd,
                runLimits.maxPhysicalRequests(),
                runLimits.maxReservedCostUsd(),
                monthlyLimits.workspaceUsd(),
                monthlyLimits.globalUsd());
    }

    @Transactional
    public boolean settle(long jobId, long usageId, int inputTokens, int outputTokens, BigDecimal actualCostUsd) {
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_close_model_usage(?, ?, 'SETTLED', ?, ?, ?)",
                Boolean.class, jobId, usageId, inputTokens, outputTokens, actualCostUsd));
    }

    @Transactional
    public boolean retain(long jobId, long usageId) {
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT public.worker_close_model_usage(?, ?, 'RETAINED', NULL, NULL, NULL)", Boolean.class, jobId, usageId));
    }

    /** Reservations a dead process never closed, kept at their full amount. Returns how many. */
    @Transactional
    public int retainStale(java.time.Duration olderThan, int limit) {
        Integer retained = jdbc().queryForObject(
                "SELECT public.worker_retain_stale_model_usage(?, ?)", Integer.class, olderThan.toMillis(), limit);
        return retained == null ? 0 : retained;
    }

    /** Audit rows past their retention period. Returns how many were removed. */
    @Transactional
    public int expireAuditEvents(java.time.Duration olderThan, int limit) {
        Integer removed = jdbc().queryForObject(
                "SELECT public.worker_expire_audit_events(?, ?)", Integer.class, olderThan.toMillis(), limit);
        return removed == null ? 0 : removed;
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate template = jdbcTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("A database-backed worker operation requires a configured datasource.");
        }
        return template;
    }
}
