package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;

    MeController(UserIdentityRepository userIdentityRepository, WorkspaceRepository workspaceRepository) {
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
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
        List<MembershipResponse> memberships = workspaceRepository.findMembershipsForUser(identity.id()).stream()
                .map(member -> new MembershipResponse(member.workspaceId(), member.role().name()))
                .toList();
        return new MeResponse(identity.id(), identity.issuer(), identity.subject(), identity.email(),
                identity.displayName(), memberships);
    }

    record MeResponse(long userId, String issuer, String subject, String email, String displayName,
                       List<MembershipResponse> memberships) {
    }

    record MembershipResponse(long workspaceId, String role) {
    }
}
