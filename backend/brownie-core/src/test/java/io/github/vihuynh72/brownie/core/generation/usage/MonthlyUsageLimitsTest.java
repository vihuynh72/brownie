package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MonthlyUsageLimitsTest {

    @Test
    void theOpenAllowanceMayBeLeftOffEntirely() {
        assertDoesNotThrow(() -> new MonthlyUsageLimits(new BigDecimal("2.00"), new BigDecimal("15.00")));
    }

    @Test
    void theOpenAllowanceMayBeBothADayAndAMonth() {
        assertDoesNotThrow(() -> new MonthlyUsageLimits(new BigDecimal("2.00"), new BigDecimal("15.00"),
                new BigDecimal("0.10"), new BigDecimal("1.00")));
    }

    @Test
    void theOpenAllowanceCannotBeOnlyADayOrOnlyAMonth() {
        assertThrows(IllegalArgumentException.class, () -> new MonthlyUsageLimits(
                new BigDecimal("2.00"), new BigDecimal("15.00"), new BigDecimal("0.10"), null));
        assertThrows(IllegalArgumentException.class, () -> new MonthlyUsageLimits(
                new BigDecimal("2.00"), new BigDecimal("15.00"), null, new BigDecimal("1.00")));
    }

    @Test
    void theOpenAllowanceMustBePositiveWhenItIsSet() {
        assertThrows(IllegalArgumentException.class, () -> new MonthlyUsageLimits(
                new BigDecimal("2.00"), new BigDecimal("15.00"), BigDecimal.ZERO, new BigDecimal("1.00")));
        assertThrows(IllegalArgumentException.class, () -> new MonthlyUsageLimits(
                new BigDecimal("2.00"), new BigDecimal("15.00"), new BigDecimal("0.10"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MonthlyUsageLimits(
                new BigDecimal("2.00"), new BigDecimal("15.00"), new BigDecimal("-0.10"), new BigDecimal("1.00")));
    }

    @Test
    void theOpenDayCannotExceedTheOpenMonth() {
        assertThrows(IllegalArgumentException.class, () -> new MonthlyUsageLimits(
                new BigDecimal("2.00"), new BigDecimal("15.00"), new BigDecimal("1.00"), new BigDecimal("0.10")));
    }

    @Test
    void theOpenMonthCannotExceedWhatEveryoneShares() {
        assertThrows(IllegalArgumentException.class, () -> new MonthlyUsageLimits(
                new BigDecimal("2.00"), new BigDecimal("15.00"), new BigDecimal("0.10"), new BigDecimal("20.00")));
    }
}
