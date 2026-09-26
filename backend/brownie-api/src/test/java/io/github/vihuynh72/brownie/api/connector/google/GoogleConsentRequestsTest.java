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

    /** Exactly what these addresses were before Google's picker could be asked for, character for character. */
    @Test
    void connectingIsUnchangedByThePicker() {
        assertThat(GoogleConsentRequests.authorizationUri(SETTINGS, ConnectorAccess.CALENDAR_EVENTS, "s", "c")).hasToString(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=client-123.apps.googleusercontent.com"
                        + "&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Fapi%2Fv1%2Fconnectors%2Fgoogle%2Fcallback&response_type=code"
                        + "&scope=openid%20email%20https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fcalendar.events.owned.readonly"
                        + "&access_type=offline&prompt=consent&state=s&code_challenge=c&code_challenge_method=S256");
        assertThat(GoogleConsentRequests.authorizationUri(SETTINGS, ConnectorAccess.DRIVE_FILES, "s", "c")).hasToString(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=client-123.apps.googleusercontent.com"
                        + "&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Fapi%2Fv1%2Fconnectors%2Fgoogle%2Fcallback&response_type=code"
                        + "&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"
                        + "&access_type=offline&prompt=consent&state=s&code_challenge=c&code_challenge_method=S256");
    }

    @Test
    void aDrivePickIsTheDriveConsentWithGooglesPickerTurnedOnForDocsAndTextFiles() {
        UriComponents parts = UriComponentsBuilder.fromUri(GoogleConsentRequests.drivePickUri(SETTINGS, "state-value", "challenge-value", true)).build();

        assertThat(decoded(parts, "scope")).as("Google allows the picker only with drive.file alone")
                .isEqualTo("https://www.googleapis.com/auth/drive.file");
        assertThat(decoded(parts, "trigger_onepick")).isEqualTo("true");
        assertThat(decoded(parts, "allow_multiple")).isEqualTo("true");
        assertThat(decoded(parts, "mimetypes")).isEqualTo("application/vnd.google-apps.document,text/plain");
        assertThat(decoded(parts, "access_type")).isEqualTo("offline");
        assertThat(decoded(parts, "prompt")).isEqualTo("consent");
        assertThat(decoded(parts, "state")).isEqualTo("state-value");
        assertThat(decoded(parts, "code_challenge")).isEqualTo("challenge-value");
        assertThat(decoded(parts, "code_challenge_method")).isEqualTo("S256");
        assertThat(parts.getQueryParams()).doesNotContainKey("include_granted_scopes");
    }

    @Test
    void aPickWithoutLastingAccessLeavesOnlyTheOfflineRequestOut() {
        UriComponents parts = UriComponentsBuilder.fromUri(GoogleConsentRequests.drivePickUri(SETTINGS, "s", "c", false)).build();

        assertThat(parts.getQueryParams()).doesNotContainKey("access_type");
        assertThat(decoded(parts, "trigger_onepick")).isEqualTo("true");
        assertThat(decoded(parts, "prompt")).isEqualTo("consent");
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
