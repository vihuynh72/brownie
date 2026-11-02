package io.github.vihuynh72.brownie.api.identity.oidc;

import org.springframework.beans.factory.annotation.Autowired;
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

    /** What kind of person a sign-in may let in. */
    public enum Access {
        /** The full allowance, either because invitations are not asked for or because this address has one. */
        FULL,
        /** Anyone may sign in; their workspace shares the open allowance instead. */
        OPEN,
        /** Not invited, and open sign-up is off. */
        REFUSED
    }

    private final boolean required;
    private final boolean openSignUp;
    private final Set<String> addresses;

    @Autowired
    public InvitedPeople(
            @Value("${brownie.invitations.required:false}") boolean required,
            @Value("${brownie.invitations.addresses:}") String addresses,
            @Value("${brownie.invitations.open-sign-up:false}") boolean openSignUp) {
        this.required = required;
        this.openSignUp = openSignUp;
        this.addresses = normalize(addresses);
    }

    /** What a test that does not care about open sign-up can build: the gate with it off. */
    InvitedPeople(boolean required, String addresses) {
        this(required, addresses, false);
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

    /**
     * Who this sign-in is. A missing or blank address can never be FULL
     * when invitations are asked for, since there is nothing to match an
     * invitation against, and guessing in the person's favour would open
     * the gate to anyone whose provider omits the claim; it is OPEN when
     * open sign-up is on and REFUSED when it is not.
     */
    public Access accessFor(String email) {
        if (!required) {
            return Access.FULL;
        }
        if (email != null && !email.isBlank() && addresses.contains(email.strip().toLowerCase(Locale.ROOT))) {
            return Access.FULL;
        }
        return openSignUp ? Access.OPEN : Access.REFUSED;
    }

    /** True when no invitation is asked for, or when this address has one, or when open sign-up lets anyone in. */
    public boolean mayUseBrownie(String email) {
        return accessFor(email) != Access.REFUSED;
    }

    /** Whether people who are not invited may still sign in on the open allowance. */
    public boolean openSignUp() {
        return openSignUp;
    }

    public boolean required() {
        return required;
    }

    /** How many people are invited. The addresses themselves are not exposed, and never logged. */
    public int invitedCount() {
        return addresses.size();
    }
}
