package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The two allowances that outlive a run: what one workspace may spend on
 * the model in a calendar month, and what everyone together may. They are
 * settings, not constants, because they are the owner's money; the ledger
 * enforces whatever it is given.
 */
public record MonthlyUsageLimits(BigDecimal workspaceUsd, BigDecimal globalUsd) {

    public MonthlyUsageLimits {
        Objects.requireNonNull(workspaceUsd, "workspaceUsd");
        Objects.requireNonNull(globalUsd, "globalUsd");
        if (workspaceUsd.signum() <= 0 || globalUsd.signum() <= 0) {
            throw new IllegalArgumentException("Monthly usage limits must be positive.");
        }
        if (workspaceUsd.compareTo(globalUsd) > 0) {
            throw new IllegalArgumentException("One workspace's monthly allowance cannot exceed the allowance everyone shares.");
        }
    }
}
