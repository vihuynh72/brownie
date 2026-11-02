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

    @Test
    void anUninvitedPersonGetsTheOpenAllowanceWhenOpenSignUpIsOn() {
        InvitedPeople people = new InvitedPeople(true, "secretary@example.com", true);

        assertThat(people.accessFor("stranger@example.com")).isEqualTo(InvitedPeople.Access.OPEN);
        assertThat(people.accessFor("secretary@example.com")).isEqualTo(InvitedPeople.Access.FULL);
        assertThat(people.accessFor(null)).isEqualTo(InvitedPeople.Access.OPEN);
        assertThat(people.accessFor("  ")).isEqualTo(InvitedPeople.Access.OPEN);
        assertThat(people.mayUseBrownie("stranger@example.com")).isTrue();
    }

    @Test
    void anUninvitedPersonIsRefusedWhenOpenSignUpIsOff() {
        InvitedPeople people = new InvitedPeople(true, "secretary@example.com", false);

        assertThat(people.accessFor("stranger@example.com")).isEqualTo(InvitedPeople.Access.REFUSED);
        assertThat(people.accessFor(null)).isEqualTo(InvitedPeople.Access.REFUSED);
        assertThat(people.mayUseBrownie("stranger@example.com")).isFalse();
    }

    @Test
    void noGateAtAllIsStillFullForAnyone() {
        InvitedPeople people = new InvitedPeople(false, "", true);

        assertThat(people.accessFor("stranger@example.com")).isEqualTo(InvitedPeople.Access.FULL);
        assertThat(people.accessFor(null)).isEqualTo(InvitedPeople.Access.FULL);
    }

    /** A gate that opens when it is misconfigured is not a gate. */
    @Test
    void theGateOnWithNobodyInvitedLetsNobodyIn() {
        InvitedPeople people = new InvitedPeople(true, "   ");

        assertThat(people.mayUseBrownie("anyone@example.com")).isFalse();
        assertThat(people.invitedCount()).isZero();
    }
}
