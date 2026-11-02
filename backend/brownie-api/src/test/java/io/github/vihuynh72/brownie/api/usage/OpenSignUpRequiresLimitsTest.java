package io.github.vihuynh72.brownie.api.usage;

import io.github.vihuynh72.brownie.api.identity.oidc.InvitedPeople;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSignUpRequiresLimitsTest {

    private static final MonthlyUsageLimits FULL = new MonthlyUsageLimits(
            new BigDecimal("2.00"), new BigDecimal("15.00"), new BigDecimal("0.10"), new BigDecimal("5.00"));
    private static final MonthlyUsageLimits NO_OPEN = new MonthlyUsageLimits(
            new BigDecimal("2.00"), new BigDecimal("15.00"), null, null);

    @Test
    void startsWhenOpenSignUpIsOnAndTheLimitsAreSet() {
        assertThatCode(() -> new OpenSignUpRequiresLimits(new InvitedPeople(true, "", true), FULL))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesToStartWhenOpenSignUpIsOnAndTheLimitsAreBlank() {
        assertThatThrownBy(() -> new OpenSignUpRequiresLimits(new InvitedPeople(true, "", true), NO_OPEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("brownie.invitations.open-sign-up")
                .hasMessageContaining("brownie.usage.open-daily-limit-usd")
                .hasMessageContaining("brownie.usage.open-monthly-limit-usd");
    }

    @Test
    void startsWhenOpenSignUpIsOffAndTheLimitsAreBlank() {
        assertThatCode(() -> new OpenSignUpRequiresLimits(new InvitedPeople(true, "", false), NO_OPEN))
                .doesNotThrowAnyException();
    }
}
