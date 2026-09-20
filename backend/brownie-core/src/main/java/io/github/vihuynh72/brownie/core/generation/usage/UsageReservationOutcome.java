package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * What the database answered when asked to record one upcoming model
 * request: a handle when it fits, otherwise the name of the limit that
 * refused it. Shared by the two ledgers (one bound to a leased job, one to
 * a signed-in person) so both turn the same words into the same exception.
 */
public record UsageReservationOutcome(String outcome, Long usageId) {

    public long requireReserved(UsageLimits runLimits, MonthlyUsageLimits monthlyLimits) throws BudgetExceededException {
        return switch (outcome) {
            case "RESERVED" -> {
                if (usageId == null) {
                    throw new IllegalStateException("A reservation was accepted without a handle.");
                }
                yield usageId;
            }
            case "RUN_LIMIT" -> throw new BudgetExceededException(UsageLimitKind.RUN,
                    "This run has reached its limit of " + modelRequests(runLimits.maxPhysicalRequests()) + " or "
                            + dollars(runLimits.maxReservedCostUsd()) + ".");
            case "WORKSPACE_MONTH_LIMIT" -> throw new BudgetExceededException(UsageLimitKind.WORKSPACE_MONTH,
                    workspaceMonthUsedUp(monthlyLimits));
            case "GLOBAL_MONTH_LIMIT" -> throw new BudgetExceededException(UsageLimitKind.GLOBAL_MONTH,
                    "The model allowance everyone shares is used up for this month.");
            case "LOST_LEASE", "NOT_PERMITTED" -> throw new BudgetExceededException(UsageLimitKind.LEASE_LOST,
                    "The request could not be recorded for its spender, so it was not sent.");
            default -> throw new IllegalStateException("Unknown usage reservation outcome: " + outcome);
        };
    }

    /** The same sentence whether the ledger refuses a request or the service refuses the work before it starts. */
    static String workspaceMonthUsedUp(MonthlyUsageLimits monthlyLimits) {
        return "This workspace has used its model allowance of " + dollars(monthlyLimits.workspaceUsd()) + " for this month.";
    }

    /**
     * An amount as it reads on a bill, because the web shows these sentences
     * word for word: "$5.00" and "$1.50", never "$5" or "$1.5". A setting
     * finer than a cent keeps its extra digits, so a limit of $0.005 is not
     * shown as a round figure it is not.
     */
    static String dollars(BigDecimal amount) {
        BigDecimal exact = amount.stripTrailingZeros();
        DecimalFormat format = new DecimalFormat("$#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        format.setMaximumFractionDigits(Math.max(2, exact.scale()));
        return format.format(exact);
    }

    static String modelRequests(int count) {
        return count == 1 ? "1 model request" : count + " model requests";
    }
}
