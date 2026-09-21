package io.github.vihuynh72.brownie.api.identity.oidc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The gate's own rules, including the two that are easy to get backwards. */
class InvitedPeopleTest {

    @Test
    void withNoGateEverybodyMayUseIt() {
        InvitedPeople people = new InvitedPeople(false, "");

        assertThat(people.mayUseBrownie("anyone@example.com")).isTrue();
        assertThat(people.mayUseBrownie(null)).isTrue();
        assertThat(people.required()).isFalse();
    }

    @Test
    void anInvitedAddressIsMatchedWhateverItsSpacingOrCase() {
        InvitedPeople people = new InvitedPeople(true, " Secretary@Example.com , second@example.com ");

        assertThat(people.mayUseBrownie("secretary@example.com")).isTrue();
        assertThat(people.mayUseBrownie("SECRETARY@EXAMPLE.COM")).isTrue();
        assertThat(people.mayUseBrownie(" second@example.com ")).isTrue();
        assertThat(people.invitedCount()).isEqualTo(2);
    }

    @Test
    void anAddressThatWasNotInvitedIsRefused() {
        InvitedPeople people = new InvitedPeople(true, "secretary@example.com");

        assertThat(people.mayUseBrownie("someone.else@example.com")).isFalse();
    }

    /** Inviting an address is not inviting everyone who shares its domain. */
    @Test
    void anInvitationIsNotADomain() {
        InvitedPeople people = new InvitedPeople(true, "secretary@example.com");

        assertThat(people.mayUseBrownie("stranger@example.com")).isFalse();
        assertThat(people.mayUseBrownie("@example.com")).isFalse();
        assertThat(people.mayUseBrownie("example.com")).isFalse();
    }

    /** A provider that sends no address leaves nothing to match, and guessing in the caller's favour would open the gate to anyone. */
    @Test
    void anAccountWithNoAddressIsRefused() {
        InvitedPeople people = new InvitedPeople(true, "secretary@example.com");

        assertThat(people.mayUseBrownie(null)).isFalse();
        assertThat(people.mayUseBrownie("  ")).isFalse();
    }

    /** A gate that opens when it is misconfigured is not a gate. */
    @Test
    void theGateOnWithNobodyInvitedLetsNobodyIn() {
        InvitedPeople people = new InvitedPeople(true, "   ");

        assertThat(people.mayUseBrownie("anyone@example.com")).isFalse();
        assertThat(people.invitedCount()).isZero();
    }
}
