package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The identity slice of the master plan's planned {@code GET /api/v1/me}
 * (§12.2). Membership summaries and effective capabilities are added once
 * P04-02/P04-03 give them something real to report; today this is the
 * end-to-end proof that login, the session, and issuer-subject mapping
 * work.
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserIdentityRepository userIdentityRepository;

    MeController(UserIdentityRepository userIdentityRepository) {
        this.userIdentityRepository = userIdentityRepository;
    }

    @GetMapping
    MeResponse me(@AuthenticationPrincipal OidcUser oidcUser) {
        String issuer = oidcUser.getIssuer().toString();
        String subject = oidcUser.getSubject();
        UserIdentity identity = userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no recorded identity for issuer/subject " + issuer + "/"
                                + subject));
        return new MeResponse(identity.id(), identity.issuer(), identity.subject(), identity.email(),
                identity.displayName());
    }

    record MeResponse(long userId, String issuer, String subject, String email, String displayName) {
    }
}
