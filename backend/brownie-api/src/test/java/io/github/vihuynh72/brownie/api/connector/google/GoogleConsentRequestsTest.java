package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleConsentRequestsTest {

    private static final GoogleClientSettings SETTINGS = new GoogleClientSettings(
            "client-123.apps.googleusercontent.com",
            "not-a-real-secret",
            URI.create("http://localhost:8081/api/v1/connectors/google/callback"),
            URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
            URI.create("https://oauth2.googleapis.com/token"),
            URI.create("https://oauth2.googleapis.com/revoke"),
            URI.create("https://openidconnect.googleapis.com/v1/userinfo"),
            URI.create("https://www.googleapis.com"),
            "https://accounts.google.com",
            Duration.ofSeconds(5),
            Duration.ofSeconds(15));

    @Test
    void theConsentAddressAsksForOfflineAccessWithConsentShownAndOnlyThisAccessesScopes() {
        URI uri = GoogleConsentRequests.authorizationUri(SETTINGS, ConnectorAccess.CALENDAR_EVENTS, "state-value", "challenge-value");
        UriComponents parts = UriComponentsBuilder.fromUri(uri).build();

        assertThat(uri.toString()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(decoded(parts, "client_id")).isEqualTo("client-123.apps.googleusercontent.com");
        assertThat(decoded(parts, "redirect_uri")).isEqualTo("http://localhost:8081/api/v1/connectors/google/callback");
        assertThat(decoded(parts, "response_type")).isEqualTo("code");
        assertThat(decoded(parts, "scope"))
                .isEqualTo("openid email https://www.googleapis.com/auth/calendar.events.owned.readonly");
        assertThat(decoded(parts, "access_type")).isEqualTo("offline");
        assertThat(decoded(parts, "prompt")).isEqualTo("consent");
        assertThat(decoded(parts, "state")).isEqualTo("state-value");
        assertThat(decoded(parts, "code_challenge")).isEqualTo("challenge-value");
        assertThat(decoded(parts, "code_challenge_method")).isEqualTo("S256");
        assertThat(parts.getQueryParams()).doesNotContainKey("include_granted_scopes");
        assertThat(uri.toString()).doesNotContain("not-a-real-secret");
    }

    @Test
    void driveAsksForTheOneFileScopeAndNothingElse() {
        URI uri = GoogleConsentRequests.authorizationUri(SETTINGS, ConnectorAccess.DRIVE_FILES, "s", "c");

        assertThat(decoded(UriComponentsBuilder.fromUri(uri).build(), "scope")).isEqualTo("https://www.googleapis.com/auth/drive.file");
    }

    @Test
    void valuesAreEncodedSoNoneCanAddAParameterOfItsOwn() {
        URI uri = GoogleConsentRequests.authorizationUri(SETTINGS, ConnectorAccess.DRIVE_FILES, "a&prompt=none", "c");

        assertThat(uri.getRawQuery()).contains("state=a%26prompt%3Dnone").containsOnlyOnce("prompt=");
    }

    /** The worked example from the PKCE specification itself (RFC 7636, appendix B). */
    @Test
    void theChallengeIsTheSha256OfTheVerifierBase64UrlEncodedWithoutPadding() {
        assertThat(GoogleConsentRequests.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void stateAndVerifierAreLongRandomAndUrlSafe() {
        String verifier = GoogleConsentRequests.newCodeVerifier();
        assertThat(verifier).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(GoogleConsentRequests.newState()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(GoogleConsentRequests.newState()).isNotEqualTo(GoogleConsentRequests.newState());
    }

    private static String decoded(UriComponents parts, String name) {
        return URLDecoder.decode(parts.getQueryParams().getFirst(name), StandardCharsets.UTF_8);
    }
}
