package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The aggregate bounds one extraction run must never exceed, whichever is
 * reached first. Values match this plan's own researched starting figures
 * for a single run: three physical model requests, 40,000 input tokens,
 * 8,000 billed output tokens, and $0.10 of reserved spend.
 */
public record UsageLimits(int maxPhysicalRequests, int maxInputTokens, int maxOutputTokens, BigDecimal maxReservedCostUsd) {

    public UsageLimits {
        Objects.requireNonNull(maxReservedCostUsd, "maxReservedCostUsd");
        if (maxPhysicalRequests <= 0 || maxInputTokens <= 0 || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("Usage limits must be positive.");
        }
        if (maxReservedCostUsd.signum() <= 0) {
            throw new IllegalArgumentException("maxReservedCostUsd must be positive.");
        }
    }

    public static UsageLimits defaultRunLimits() {
        return new UsageLimits(3, 40_000, 8_000, new BigDecimal("0.10"));
    }
}
