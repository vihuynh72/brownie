package io.github.vihuynh72.brownie.api.artifact;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Allocate an owned upload, stream its content, finalize it, then later
 * read it back -- each a separate request because a client's upload
 * attempt can fail or be retried between any of them, and each one
 * re-checks membership and capability rather than trusting that an
 * earlier check in the same flow still holds.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/uploads")
class ArtifactController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);
    private static final int COPY_BUFFER_SIZE = 8192;

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

    /**
     * Serves a READY artifact's exact stored bytes with an {@code inline}
     * disposition, for a client that wants to display it directly (a
     * browser natively renders a PDF or plain text this way; it does not
     * for a DOCX, which is why nothing here promises a rendered preview --
     * only that the bytes are what was actually stored).
     */
    @GetMapping("/{artifactId}/preview")
    void preview(
            @PathVariable long workspaceId,
            @PathVariable long artifactId,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletResponse response)
            throws IOException {
        serveContent(workspaceId, artifactId, principal, response, ContentDisposition.inline());
    }

    /** Serves a READY artifact's exact stored bytes with an {@code attachment} disposition, forcing a save-as. */
    @GetMapping("/{artifactId}/download")
    void download(
            @PathVariable long workspaceId,
            @PathVariable long artifactId,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletResponse response)
            throws IOException {
        serveContent(workspaceId, artifactId, principal, response, ContentDisposition.attachment());
    }

    /**
     * Proxies the artifact's bytes straight through this authenticated,
     * per-request-authorized route rather than issuing the client a
     * signed storage URL of its own -- a URL handed out once cannot
     * actually be revoked before it expires, while a route that re-checks
     * membership, capability, and READY status on every call can refuse
     * the very next request the moment any of those stop holding.
     */
    private void serveContent(
            long workspaceId,
            long artifactId,
            OidcUser principal,
            HttpServletResponse response,
            ContentDisposition.Builder disposition)
            throws IOException {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        try (ReadableArtifact readable = artifactService.openContent(workspaceId, userId, artifactId)) {
            Artifact artifact = readable.artifact();
            response.setContentType(artifact.detectedMediaType().mimeType());
            response.setContentLengthLong(artifact.byteCount());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    disposition
                            .filename(downloadFilename(artifact), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            copy(readable.content(), response.getOutputStream(), artifactId);
        }
    }

    /**
     * A manual copy loop, not {@link InputStream#transferTo}, specifically
     * so a failure reading the stored object and a failure writing to the
     * client are told apart -- {@code transferTo} reports both the same
     * way, and they are not the same kind of problem. By the time this
     * runs, headers and a fixed {@code Content-Length} are already
     * committed to the response, so neither case has a well-formed error
     * body left to send back; the only thing left to get right is what
     * gets logged, and at what severity. A read failure means the stored
     * object itself could not be read -- a real, worth-alerting-on
     * problem. A write failure almost always means the client went away
     * mid-download (a closed tab, a cancelled transfer, a dropped
     * connection) -- routine, expected, and not this server's fault; it is
     * swallowed quietly rather than reaching the generic unhandled-
     * exception handler, which would otherwise log it as a server error
     * and then itself fail trying to write a fresh JSON body onto a
     * response that can no longer accept one.
     */
    static void copy(InputStream content, OutputStream out, long artifactId) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        while (true) {
            int read;
            try {
                read = content.read(buffer);
            } catch (IOException e) {
                log.error("Failed to read stored content for artifact {} while serving it.", artifactId, e);
                return;
            }
            if (read == -1) {
                return;
            }
            try {
                out.write(buffer, 0, read);
            } catch (IOException e) {
                log.debug("Client disconnected while receiving artifact {} content.", artifactId, e);
                return;
            }
        }
    }

    /** Falls back to a generated name only when the artifact was allocated with none -- never an empty or missing filename. */
    private static String downloadFilename(Artifact artifact) {
        if (artifact.displayFilename() != null && !artifact.displayFilename().isBlank()) {
            return artifact.displayFilename();
        }
        return "artifact-" + artifact.id() + "." + artifact.detectedMediaType().defaultFileExtension();
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
