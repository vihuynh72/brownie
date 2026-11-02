package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private static final Logger log = LoggerFactory.getLogger(MeController.class);

    private final UserIdentityRepository userIdentityRepository;
    private final WorkspaceRepository workspaceRepository;

    MeController(UserIdentityRepository userIdentityRepository, WorkspaceRepository workspaceRepository) {
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
    }

    @GetMapping
    MeResponse me(@AuthenticationPrincipal OidcUser oidcUser) {
        UserIdentity identity = identityOf(oidcUser);
        List<MembershipResponse> memberships = workspaceRepository.findMembershipsForUser(identity.id()).stream()
                .map(member -> new MembershipResponse(member.workspaceId(), member.role().name()))
                .toList();
        String allowance = workspaceRepository.findOpenAllowance(identity.id()).orElse(false)
                ? "FREE_DAILY"
                : "FULL";
        return new MeResponse(identity.id(), identity.issuer(), identity.subject(), identity.email(),
                identity.displayName(), allowance, userIdentityRepository.hasInvitationRequest(identity.id()),
                memberships);
    }

    /** An uninvited person's one way to ask: remembered, not answered here. */
    @PostMapping("/invitation-request")
    ResponseEntity<Void> invitationRequest(@AuthenticationPrincipal OidcUser oidcUser,
            @RequestBody(required = false) InvitationRequestBody body) {
        UserIdentity identity = identityOf(oidcUser);
        String note = body == null ? null : body.note();
        if (note != null && note.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A note may be at most 500 characters.");
        }
        userIdentityRepository.recordInvitationRequest(identity.id(), note);
        log.info("An invitation request was recorded.");
        return ResponseEntity.noContent().build();
    }

    private UserIdentity identityOf(OidcUser oidcUser) {
        String issuer = oidcUser.getIssuer().toString();
        String subject = oidcUser.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(AuthenticatedIdentityMissingException::new);
    }

    record MeResponse(long userId, String issuer, String subject, String email, String displayName,
                       String allowance, boolean invitationRequested,
                       List<MembershipResponse> memberships) {
    }

    record MembershipResponse(long workspaceId, String role) {
    }

    record InvitationRequestBody(String note) {
    }
}
