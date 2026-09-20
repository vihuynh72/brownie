package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageReservationOutcome;
import io.github.vihuynh72.brownie.core.generation.usage.UsageSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Writes the usage ledger only through its routines; this login holds no
 * INSERT, UPDATE or DELETE on the table. Reservations and their closing run
 * in a transaction of their own on purpose: a request that was sent has to
 * stay written down even when the work that needed it is rolled back.
 */
@Repository
class JdbcMemberUsageRepository implements MemberUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcMemberUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UsageReservationOutcome reserve(
            long workspaceId,
            long userId,
            String modelName,
            String promptVersion,
            String rateCard,
            int estimatedInputTokens,
            int estimatedMaxOutputTokens,
            BigDecimal estimatedCostUsd,
            BigDecimal runLimitUsd,
            MonthlyUsageLimits monthlyLimits) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.queryForObject(
                "SELECT outcome, usage_id FROM reserve_member_model_usage(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (rs, rowNum) -> new UsageReservationOutcome(rs.getString("outcome"), (Long) rs.getObject("usage_id")),
                workspaceId,
                modelName,
                promptVersion,
                rateCard,
                estimatedInputTokens,
                estimatedMaxOutputTokens,
                estimatedCostUsd,
                runLimitUsd,
                monthlyLimits.workspaceUsd(),
                monthlyLimits.globalUsd());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean settle(long workspaceId, long userId, long usageId, int inputTokens, int outputTokens, BigDecimal actualCostUsd) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT close_member_model_usage(?, ?, 'SETTLED', ?, ?, ?)",
                Boolean.class, workspaceId, usageId, inputTokens, outputTokens, actualCostUsd));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retain(long workspaceId, long userId, long usageId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT close_member_model_usage(?, ?, 'RETAINED', NULL, NULL, NULL)", Boolean.class, workspaceId, usageId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UsageSummary> summary(long workspaceId, long userId, BigDecimal globalMonthLimitUsd, BigDecimal nextRequestUsd) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT workspace_month_cost_usd, workspace_month_requests, global_allowance_exhausted"
                                + " FROM member_model_usage_summary(?, ?, ?)",
                        (rs, rowNum) -> new UsageSummary(
                                rs.getBigDecimal("workspace_month_cost_usd"),
                                rs.getLong("workspace_month_requests"),
                                rs.getBoolean("global_allowance_exhausted")),
                        workspaceId,
                        globalMonthLimitUsd,
                        nextRequestUsd)
                .stream()
                .findFirst();
    }
}
