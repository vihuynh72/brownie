package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.api.job.CanonicalRequestHasher;
import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.template.FillSpotReviewService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A correction makes a new version of the form that every later document
 * starts from, so it needs the right to change templates, as the template
 * routes do; moving one document to a version that is already there does
 * not. Every member has the OWNER role today, which grants every right,
 * so a member without that right is stood in for here.
 */
class FillSpotControllerTest {

    private static final String ISSUER = "https://issuer-fill-spot-controller";
    private static final long USER_ID = 7L;
    private static final long WORKSPACE_ID = 1L;

    private final FillSpotService fillSpotService = mock(FillSpotService.class);
    private final WorkspaceAuthorizationService authorization = mock(WorkspaceAuthorizationService.class);
    private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
    private final FillSpotController controller = new FillSpotController(
            fillSpotService, mock(FillSpotReviewService.class), mock(CanonicalRequestHasher.class), authorization, identities);

    @Test
    void aCorrectionNeedsTheRightToChangeTemplatesAndAMoveDoesNot() {
        when(identities.findByIssuerAndSubject(ISSUER, "subject")).thenReturn(Optional.of(
                new UserIdentity(USER_ID, ISSUER, "subject", null, null, null, null, null)));
        doThrow(new AccessDeniedException("Role does not grant MANAGE_TEMPLATES."))
                .when(authorization).requireCapability(USER_ID, WORKSPACE_ID, WorkspaceCapability.MANAGE_TEMPLATES);

        assertThatThrownBy(() -> controller.changeFillSpots(
                        WORKSPACE_ID, 5L, new FillSpotController.FillSpotsRequest(2L, 3L, List.of()), UUID.randomUUID().toString(), principal()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(fillSpotService);

        when(fillSpotService.moveToTemplateVersion(anyLong(), anyLong(), any(), any(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("moved"));
        assertThatThrownBy(() -> controller.moveToTemplateVersion(
                        WORKSPACE_ID, 5L, new FillSpotController.TemplateVersionMoveRequest(2L, 3L), UUID.randomUUID().toString(), principal()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("moved");
    }

    private static OidcUser principal() {
        OidcIdToken token = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER)
                .claim(IdTokenClaimNames.SUB, "subject")
                .build();
        return new DefaultOidcUser(List.of(), token);
    }
}
