package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The address a person is sent to so that they can agree, on Google's own
 * pages, to one kind of access, and the two secrets that tie Google's answer
 * back to this request.
 *
 * <p>{@code state} is a random value kept in the person's session and
 * compared when Google sends them back, so an answer started from anywhere
 * else is refused. The code verifier is a second random value, kept in the
 * same place and never sent to the browser; Google receives only its SHA-256
 * digest now and the verifier itself later, with the code, so a code that
 * leaks on its way back through the browser cannot be exchanged by anyone
 * else. {@code access_type=offline} with {@code prompt=consent} is what makes
 * Google hand back a refresh token every time, including when the person has
 * agreed before and is connecting again.
 *
 * <p>The same address, with Google's picker turned on, is how a person
 * chooses Drive files: Google shows its own file picker after the consent and
 * sends the chosen files' ids back with the code. Google allows only the
 * {@code drive.file} permission on it, which is all a Drive consent asks for.
 */
public final class GoogleConsentRequests {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    private GoogleConsentRequests() {
    }

    /** The documents that can be imported: Google Docs and plain-text files. The picker shows only these. */
    private static final String PICKABLE_TYPES = "application/vnd.google-apps.document,text/plain";

    public static URI authorizationUri(GoogleClientSettings settings, ConnectorAccess access, String state, String codeChallenge) {
        return build(settings, access, state, codeChallenge, true, false);
    }

    /**
     * Google's file picker for Drive. {@code offline} asks for a refresh token
     * too, as connecting does; without it Google may send only a short-lived
     * access token with the chosen ids.
     */
    public static URI drivePickUri(GoogleClientSettings settings, String state, String codeChallenge, boolean offline) {
        return build(settings, ConnectorAccess.DRIVE_FILES, state, codeChallenge, offline, true);
    }

    private static URI build(
            GoogleClientSettings settings, ConnectorAccess access, String state, String codeChallenge, boolean offline, boolean pick) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("client_id", settings.clientId());
        values.put("redirect_uri", settings.redirectUri().toString());
        values.put("scope", String.join(" ", GoogleScopes.requested(access)));
        values.put("state", state);
        values.put("code_challenge", codeChallenge);
        values.put("mimetypes", PICKABLE_TYPES);
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(settings.authorizationUri())
                .queryParam("client_id", "{client_id}")
                .queryParam("redirect_uri", "{redirect_uri}")
                .queryParam("response_type", "code")
                .queryParam("scope", "{scope}");
        if (offline) {
            builder.queryParam("access_type", "offline");
        }
        builder.queryParam("prompt", "consent")
                .queryParam("state", "{state}")
                .queryParam("code_challenge", "{code_challenge}")
                .queryParam("code_challenge_method", "S256");
        if (pick) {
            builder.queryParam("trigger_onepick", "true")
                    .queryParam("allow_multiple", "true")
                    .queryParam("mimetypes", "{mimetypes}");
        }
        // Encoding the template before expanding it encodes each value strictly, reserved characters included.
        return builder.encode().buildAndExpand(values).toUri();
    }

    /** 256 random bits, URL-safe. */
    public static String newState() {
        return randomToken();
    }

    /** 256 random bits, 43 URL-safe characters: inside the 43 to 128 the verifier is allowed. */
    public static String newCodeVerifier() {
        return randomToken();
    }

    public static String challengeFor(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return URL_SAFE.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_SAFE.encodeToString(bytes);
    }
}
