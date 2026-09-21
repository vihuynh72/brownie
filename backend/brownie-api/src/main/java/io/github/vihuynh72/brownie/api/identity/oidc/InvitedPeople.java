package io.github.vihuynh72.brownie.api.identity.oidc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who is allowed to sign in at all.
 *
 * <p>The identity provider's own sign-up is open by design: anyone who
 * reaches the address can make an account with it. That is right for a
 * product and wrong for a pilot of about ten invited people, so when this is
 * switched on an account is only useful if the address it signed up with was
 * invited.
 *
 * <p>It is a list of addresses in configuration rather than a table with a
 * screen behind it, because ten people do not need a screen and one that
 * nobody has used is worse than none. Adding someone means changing the
 * setting and restarting, which for a pilot is a minute's work and leaves an
 * obvious record of who was let in and when.
 *
 * <p>Two deliberate choices. Whole addresses only, never a domain: letting in
 * everyone at a university is not an invitation. And when the gate is on and
 * the list is empty, nobody gets in, including whoever deployed it -- a gate
 * that opens when it is misconfigured is not a gate, and the failure is loud
 * and immediate rather than quiet and permanent.
 *
 * <p>The address is how an invitation is matched, not who somebody is:
 * identity remains the provider's issuer and subject, as everywhere else.
 */
@Component
public class InvitedPeople {

    private final boolean required;
    private final Set<String> addresses;

    public InvitedPeople(
            @Value("${brownie.invitations.required:false}") boolean required,
            @Value("${brownie.invitations.addresses:}") String addresses) {
        this.required = required;
        this.addresses = normalize(addresses);
    }

    private static Set<String> normalize(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::strip)
                .filter(address -> !address.isEmpty())
                .map(address -> address.toLowerCase(Locale.ROOT))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    /** True when no invitation is asked for, or when this address has one. */
    public boolean mayUseBrownie(String email) {
        if (!required) {
            return true;
        }
        if (email == null || email.isBlank()) {
            // Without an address there is nothing to match an invitation
            // against, and guessing in the person's favour would open the
            // gate to anyone whose provider omits the claim.
            return false;
        }
        return addresses.contains(email.strip().toLowerCase(Locale.ROOT));
    }

    public boolean required() {
        return required;
    }

    /** How many people are invited. The addresses themselves are not exposed, and never logged. */
    public int invitedCount() {
        return addresses.size();
    }
}
