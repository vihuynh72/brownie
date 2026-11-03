package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.api.identity.oidc.InvitedPeople;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which sign-up mode the landing page is told the deployment is in. */
class WelcomeControllerTest {

    @Test
    void invitationsNotRequiredIsOpen() {
        WelcomeController controller = new WelcomeController(new InvitedPeople(false, "", false), emptyRegistrations(), false, "", false);

        assertThat(controller.welcome().signUp()).isEqualTo("OPEN");
        assertThat(controller.welcome().mcpServerUrl()).isNull();
    }

    @Test
    void invitationsRequiredAndOpenSignUpIsOpenWithADailyAllowance() {
        WelcomeController controller = new WelcomeController(new InvitedPeople(true, "a@example.com", true), emptyRegistrations(), false, "", false);

        assertThat(controller.welcome().signUp()).isEqualTo("OPEN_WITH_DAILY_ALLOWANCE");
    }

    @Test
    void invitationsRequiredAndNoOpenSignUpIsInvitationOnly() {
        WelcomeController controller = new WelcomeController(new InvitedPeople(true, "a@example.com", false), emptyRegistrations(), false, "", false);

        assertThat(controller.welcome().signUp()).isEqualTo("INVITATION_ONLY");
    }

    @Test
    void theProvidersTheDeploymentRunsAreListedGoogleFirst() {
        InMemoryClientRegistrationRepository repo = new InMemoryClientRegistrationRepository(
                registration("entra"), registration("google"));
        WelcomeController controller = new WelcomeController(new InvitedPeople(false, "", false), repo, false, "", true);

        assertThat(controller.welcome().signInProviders()).containsExactly("google", "entra");
    }

    @Test
    void theEmailCodeIsPrimaryUntilGoogleIs() {
        InMemoryClientRegistrationRepository repo = new InMemoryClientRegistrationRepository(
                registration("entra"), registration("google"));
        WelcomeController controller = new WelcomeController(new InvitedPeople(false, "", false), repo, false, "", false);

        assertThat(controller.welcome().signInProviders()).containsExactly("entra", "google");
    }

    @Test
    void anEntraOnlyDeploymentOffersOnlyTheEmailCode() {
        InMemoryClientRegistrationRepository repo = new InMemoryClientRegistrationRepository(registration("entra"));
        WelcomeController controller = new WelcomeController(new InvitedPeople(false, "", false), repo, false, "", false);

        assertThat(controller.welcome().signInProviders()).containsExactly("entra");
    }

    @Test
    void theChatAppUrlOnlyShowsWhileMcpIsOn() {
        WelcomeController withMcp = new WelcomeController(new InvitedPeople(false, "", false), emptyRegistrations(), true, "https://api.example.com", false);
        WelcomeController withoutMcp = new WelcomeController(new InvitedPeople(false, "", false), emptyRegistrations(), false, "https://api.example.com", false);

        assertThat(withMcp.welcome().mcpServerUrl()).isEqualTo("https://api.example.com/mcp");
        assertThat(withoutMcp.welcome().mcpServerUrl()).isNull();
    }
    private static InMemoryClientRegistrationRepository emptyRegistrations() {
        return new InMemoryClientRegistrationRepository(registration("entra"));
    }

    private static ClientRegistration registration(String id) {
        return ClientRegistration.withRegistrationId(id)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId("x").redirectUri("{baseUrl}/login/oauth2/code/" + id)
                .authorizationUri("https://provider.example/authorize")
                .tokenUri("https://provider.example/token")
                .build();
    }
}
