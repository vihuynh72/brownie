package io.github.vihuynh72.brownie.api.transcription;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.model.Transcription;
import io.github.vihuynh72.brownie.core.model.TranscriptionGateway;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A voice message for the chat composers: the browser records, this route
 * turns the bytes into words for the person to edit and send. The audio is
 * only ever in memory -- nothing of it is stored, logged or made an
 * artifact, ever. Like every signed-in write the route needs the session
 * and CSRF, and it costs the member the way a model call does.
 */
@RestController
class TranscriptionController {

    /** No recording longer than two minutes and no body bigger than ten megabytes plus the multipart wrapping. */
    static final long MAX_BODY_BYTES = 10L * 1024 * 1024 + 64 * 1024;
    static final long MAX_AUDIO_BYTES = 10L * 1024 * 1024;
    static final double MAX_SECONDS = 120.0;

    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;
    private final MemberUsageRepositoryHolder holder;
    private final ObjectProvider<TranscriptionGateway> gatewayProvider;
    private final String modelName;

    TranscriptionController(
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository,
            MemberUsageRepositoryHolder holder,
            ObjectProvider<TranscriptionGateway> gatewayProvider,
            @org.springframework.beans.factory.annotation.Value("${brownie.transcription.model:gpt-4o-transcribe}")
                    String modelName) {
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
        this.holder = holder;
        this.gatewayProvider = gatewayProvider;
        this.modelName = modelName;
    }

    @PostMapping("/api/v1/workspaces/{workspaceId}/transcriptions")
    Map<String, Object> transcribe(
            @PathVariable("workspaceId") long workspaceId,
            @AuthenticationPrincipal OidcUser principal,
            HttpServletRequest request)
            throws IOException {
        long userId = currentUserId(principal);
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_ARTIFACTS);
        TranscriptionGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Voice input is not available on this deployment.");
        }
        byte[] audio = audioPart(request);
        if (audio == null || audio.length == 0 || audio.length > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    audio == null || audio.length == 0 ? "No audio was sent." : "The recording is too large.");
        }
        String kind = AudioKinds.sniff(audio);
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Voice notes must be webm, ogg, m4a or wav audio.");
        }
        Transcription answer = new TranscriptionService(holder.repository(), gateway, modelName, holder.monthlyUsageLimits())
                .transcribe(workspaceId, userId, audio, "voice." + kind);
        if (answer.durationSeconds() != null && answer.durationSeconds() > MAX_SECONDS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The recording is longer than two minutes.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", answer.text());
        if (answer.language() != null) {
            out.put("language", answer.language());
        }
        if (answer.durationSeconds() != null) {
            out.put("duration_seconds", answer.durationSeconds());
        }
        return out;
    }

    /** Reads the body bounded, then takes the {@code audio} part of a multipart body or the body itself. */
    private static byte[] audioPart(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes((int) MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The recording is too large.");
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            return body;
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9).strip();
        return MultipartParser.partNamed(body, boundary, "audio");
    }

    private long currentUserId(OidcUser principal) {
        return userIdentityRepository
                .findByIssuerAndSubject(principal.getIssuer().toString(), principal.getSubject())
                .orElseThrow(() -> new IllegalStateException("Signed in without a known identity."))
                .id();
    }

    /** The magic bytes of the kinds the route takes; the declared type is never trusted. */
    static final class AudioKinds {
        private AudioKinds() {}
        static String sniff(byte[] b) {
            if (b.length >= 4 && b[0] == 0x1A && b[1] == 0x45 && b[2] == (byte) 0xDF && b[3] == (byte) 0xA3) return "webm";
            if (b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "ogg";
            if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return "wav";
            if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "m4a";
            return null;
        }
    }
}
