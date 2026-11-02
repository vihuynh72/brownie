package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.api.identity.oidc.InvitedPeople;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    WelcomeController(
            InvitedPeople invitedPeople,
            @Value("${brownie.mcp.enabled:false}") boolean mcpEnabled,
            @Value("${brownie.public-origin:}") String publicOrigin) {
        this.invitedPeople = invitedPeople;
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
        return new WelcomeResponse(signUp, mcpServerUrl);
    }

    record WelcomeResponse(String signUp, String mcpServerUrl) {
    }
}
