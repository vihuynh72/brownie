package io.github.vihuynh72.brownie.api.identity;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.detail.SavedDetailRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    private final SavedDetailRepository savedDetailRepository;

    MeController(
            UserIdentityRepository userIdentityRepository,
            WorkspaceRepository workspaceRepository,
            SavedDetailRepository savedDetailRepository) {
        this.userIdentityRepository = userIdentityRepository;
        this.workspaceRepository = workspaceRepository;
        this.savedDetailRepository = savedDetailRepository;
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
        List<io.github.vihuynh72.brownie.api.detail.SavedDetailResponse> details =
                savedDetailRepository.list(identity.id()).stream()
                        .map(detail -> new io.github.vihuynh72.brownie.api.detail.SavedDetailResponse(
                                detail.key(), detail.label(),
                                io.github.vihuynh72.brownie.core.detail.DetailKey.find(detail.key())
                                        .map(each -> each.group().name()).orElse("SENSITIVE"),
                                detail.sensitive(), detail.valueText(), detail.valueHint(), detail.origin().name(),
                                detail.sourceDocumentId() == null && detail.sourceLabel() == null && detail.sourceTitle() == null
                                        ? null
                                        : new io.github.vihuynh72.brownie.api.detail.DetailSource(
                                                detail.sourceDocumentId(), detail.sourceLabel(), detail.sourceTitle()),
                                detail.savedAt(), detail.confirmedAt(), 0, 0))
                        .toList();
        return new MeResponse(identity.id(), identity.issuer(), identity.subject(), identity.email(),
                identity.displayName(), identity.givenName(), identity.familyName(),
                identity.nameConfirmedAt() != null, allowance,
                userIdentityRepository.hasInvitationRequest(identity.id()), memberships, details);
    }

    /**
     * The name the person typed themselves, saved on the identity and
     * mirrored into their details so a form can fill it without asking
     * again. Each part is 1-100 characters after trimming.
     */
    @PutMapping("/name")
    MeResponse saveName(@AuthenticationPrincipal OidcUser oidcUser, @RequestBody SaveNameRequest body) {
        UserIdentity identity = identityOf(oidcUser);
        String givenName = requireNamePart(body == null ? null : body.givenName(), "givenName");
        String familyName = requireNamePart(body == null ? null : body.familyName(), "familyName");
        identity = userIdentityRepository.recordName(identity.id(), givenName, familyName);
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
        saveDetail(identity.id(), workspaceId, "person.first_name", "First name", givenName);
        saveDetail(identity.id(), workspaceId, "person.last_name", "Last name", familyName);
        saveDetail(identity.id(), workspaceId, "person.full_name", "Full name", givenName + " " + familyName);
        return me(oidcUser);
    }

    private void saveDetail(long userId, long workspaceId, String key, String label, String value) {
        savedDetailRepository.upsert(
                userId, workspaceId, key, label, value, null, null, false,
                io.github.vihuynh72.brownie.core.detail.DetailOrigin.MANUAL, null, null, null);
    }

    private static String requireNamePart(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, field + " is 1-100 characters.");
        }
        return trimmed;
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
                       String givenName, String familyName, boolean nameConfirmed,
                       String allowance, boolean invitationRequested,
                       List<MembershipResponse> memberships,
                       List<io.github.vihuynh72.brownie.api.detail.SavedDetailResponse> savedDetails) {
    }

    record MembershipResponse(long workspaceId, String role) {
    }

    record SaveNameRequest(String givenName, String familyName) {
    }

    record InvitationRequestBody(String note) {
    }
}
