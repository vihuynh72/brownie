package io.github.vihuynh72.brownie.api.artifact;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Allocate an owned upload, stream its content, then finalize it -- three
 * separate requests because a client's upload attempt can fail or be
 * retried between any of them, and each one re-checks membership and
 * capability rather than trusting that an earlier check in the same flow
 * still holds.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/uploads")
class ArtifactController {

    private final ArtifactService artifactService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;

    ArtifactController(
            ArtifactService artifactService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository) {
        this.artifactService = artifactService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ArtifactResponse allocate(
            @PathVariable long workspaceId,
            @AuthenticationPrincipal OidcUser principal,
            @RequestBody(required = false) AllocateUploadRequest request) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        String filename = request == null ? null : request.filename();
        return ArtifactResponse.from(artifactService.initiateUpload(workspaceId, userId, filename));
    }

    @PutMapping("/{artifactId}/content")
    ArtifactResponse uploadContent(
            @PathVariable long workspaceId,
            @PathVariable long artifactId,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletRequest request)
            throws IOException {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        Artifact artifact = artifactService.receiveContent(workspaceId, userId, artifactId, request.getInputStream());
        return ArtifactResponse.from(artifact);
    }

    @PostMapping("/{artifactId}/complete")
    ArtifactResponse complete(
            @PathVariable long workspaceId, @PathVariable long artifactId, @AuthenticationPrincipal OidcUser principal) {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        return ArtifactResponse.from(artifactService.finalizeUpload(workspaceId, userId, artifactId));
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no recorded identity for issuer/subject " + issuer + "/"
                                + subject))
                .id();
    }

    /** {@code filename} is optional, sanitized server-side into pure display metadata before it is ever persisted. */
    record AllocateUploadRequest(String filename) {
    }

    record ArtifactResponse(
            long id,
            String status,
            Long byteCount,
            String sha256,
            String detectedMediaType,
            String displayFilename,
            String rejectionReason) {
        static ArtifactResponse from(Artifact artifact) {
            return new ArtifactResponse(
                    artifact.id(),
                    artifact.status().name(),
                    artifact.byteCount(),
                    artifact.sha256(),
                    artifact.detectedMediaType() == null ? null : artifact.detectedMediaType().name(),
                    artifact.displayFilename(),
                    artifact.rejectionReason());
        }
    }
}
