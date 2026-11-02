package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The two allowances that outlive a run: what one workspace may spend on
 * the model in a calendar month, and what everyone together may. They are
 * settings, not constants, because they are the owner's money; the ledger
 * enforces whatever it is given.
 */
public record MonthlyUsageLimits(BigDecimal workspaceUsd, BigDecimal globalUsd, BigDecimal openDayUsd, BigDecimal openMonthUsd) {

    public MonthlyUsageLimits(BigDecimal workspaceUsd, BigDecimal globalUsd) {
        this(workspaceUsd, globalUsd, null, null);
    }

    public MonthlyUsageLimits {
        Objects.requireNonNull(workspaceUsd, "workspaceUsd");
        Objects.requireNonNull(globalUsd, "globalUsd");
        if (workspaceUsd.signum() <= 0 || globalUsd.signum() <= 0) {
            throw new IllegalArgumentException("Monthly usage limits must be positive.");
        }
        if (workspaceUsd.compareTo(globalUsd) > 0) {
            throw new IllegalArgumentException("One workspace's monthly allowance cannot exceed the allowance everyone shares.");
        }
        if ((openDayUsd == null) != (openMonthUsd == null)) {
            throw new IllegalArgumentException("The open allowance is both a daily and a monthly limit, or neither.");
        }
        if (openDayUsd != null && (openDayUsd.signum() <= 0 || openMonthUsd.signum() <= 0)) {
            throw new IllegalArgumentException("The open allowance's limits must be positive.");
        }
        if (openDayUsd != null && (openDayUsd.compareTo(openMonthUsd) > 0 || openMonthUsd.compareTo(globalUsd) > 0)) {
            throw new IllegalArgumentException("The open allowance's day cannot exceed its month, nor its month the allowance everyone shares.");
        }
    }
}
