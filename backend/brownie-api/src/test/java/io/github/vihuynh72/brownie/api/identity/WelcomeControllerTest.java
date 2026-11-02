package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.api.identity.oidc.InvitedPeople;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which sign-up mode the landing page is told the deployment is in. */
class WelcomeControllerTest {

    @Test
    void invitationsNotRequiredIsOpen() {
        WelcomeController controller = new WelcomeController(new InvitedPeople(false, "", false), false, "");

        assertThat(controller.welcome().signUp()).isEqualTo("OPEN");
        assertThat(controller.welcome().mcpServerUrl()).isNull();
    }

    @Test
    void invitationsRequiredAndOpenSignUpIsOpenWithADailyAllowance() {
        WelcomeController controller = new WelcomeController(new InvitedPeople(true, "a@example.com", true), false, "");

        assertThat(controller.welcome().signUp()).isEqualTo("OPEN_WITH_DAILY_ALLOWANCE");
    }

    @Test
    void invitationsRequiredAndNoOpenSignUpIsInvitationOnly() {
        WelcomeController controller = new WelcomeController(new InvitedPeople(true, "a@example.com", false), false, "");

        assertThat(controller.welcome().signUp()).isEqualTo("INVITATION_ONLY");
    }

    @Test
    void theChatAppUrlOnlyShowsWhileMcpIsOn() {
        WelcomeController withMcp = new WelcomeController(new InvitedPeople(false, "", false), true, "https://api.example.com");
        WelcomeController withoutMcp = new WelcomeController(new InvitedPeople(false, "", false), false, "https://api.example.com");

        assertThat(withMcp.welcome().mcpServerUrl()).isEqualTo("https://api.example.com/mcp");
        assertThat(withoutMcp.welcome().mcpServerUrl()).isNull();
    }
}
