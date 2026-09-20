package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What one workspace has used of its model allowance this calendar month,
 * and whether the allowance everyone shares is used up. The shared figure
 * itself is deliberately absent: what other people spent is not this
 * person's to see, only whether it stops them.
 */
public record UsageSummary(BigDecimal workspaceMonthCostUsd, long workspaceMonthRequests, boolean sharedAllowanceExhausted) {

    public UsageSummary {
        Objects.requireNonNull(workspaceMonthCostUsd, "workspaceMonthCostUsd");
    }
}
