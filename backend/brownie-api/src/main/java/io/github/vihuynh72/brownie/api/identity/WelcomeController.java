package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.api.identity.oidc.InvitedPeople;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What the landing page can say before anyone signs in: whether this
 * deployment lets anyone in, lets anyone in on the free allowance, or only
 * invited people, and where a chat app would point. It carries no personal
 * data and is answered without a session.
 */
@RestController
class WelcomeController {

    private final InvitedPeople invitedPeople;
    private final String mcpServerUrl;
    private final ClientRegistrationRepository clientRegistrationRepository;

    WelcomeController(
            InvitedPeople invitedPeople,
            ClientRegistrationRepository clientRegistrationRepository,
            @Value("${brownie.mcp.enabled:false}") boolean mcpEnabled,
            @Value("${brownie.public-origin:}") String publicOrigin) {
        this.invitedPeople = invitedPeople;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.mcpServerUrl = mcpEnabled ? publicOrigin + "/mcp" : null;
    }

    @GetMapping("/api/v1/welcome")
    WelcomeResponse welcome() {
        String signUp;
        if (!invitedPeople.required()) {
            signUp = "OPEN";
        } else if (invitedPeople.openSignUp()) {
            signUp = "OPEN_WITH_DAILY_ALLOWANCE";
        } else {
            signUp = "INVITATION_ONLY";
        }
        // Google first: it is the one big button, and the order is the
        // order the page draws its choices in.
        List<String> providers = List.of("google", "entra").stream()
                .filter(id -> clientRegistrationRepository.findByRegistrationId(id) != null)
                .toList();
        return new WelcomeResponse(signUp, mcpServerUrl, providers);
    }

    record WelcomeResponse(String signUp, String mcpServerUrl, List<String> signInProviders) {
    }
}
