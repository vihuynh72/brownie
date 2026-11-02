package io.github.vihuynh72.brownie.api.usage;

import io.github.vihuynh72.brownie.api.identity.oidc.InvitedPeople;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the deployment would let strangers in without a
 * budget of their own: invitations required and open sign-up on, but no
 * open daily/monthly limit set -- every uninvited sign-in would land on the
 * invited people's allowance instead of the free one. Names both settings so
 * whoever deploys can see what to fix.
 */
@Component
class OpenSignUpRequiresLimits {

    OpenSignUpRequiresLimits(InvitedPeople invitedPeople, MonthlyUsageLimits monthlyUsageLimits) {
        if (invitedPeople.required()
                && invitedPeople.openSignUp()
                && (monthlyUsageLimits.openDayUsd() == null || monthlyUsageLimits.openMonthUsd() == null)) {
            throw new IllegalStateException(
                    "brownie.invitations.open-sign-up is on while invitations are required, but "
                            + "brownie.usage.open-daily-limit-usd and brownie.usage.open-monthly-limit-usd "
                            + "are not both set: a stranger would sign in on the invited people's allowance.");
        }
    }
}
