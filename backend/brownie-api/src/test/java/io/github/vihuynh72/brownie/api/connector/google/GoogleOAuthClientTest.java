package io.github.vihuynh72.brownie.api.connector.google;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorBlockedByOrganizationException;
import io.github.vihuynh72.brownie.core.connector.ProviderAccount;
import io.github.vihuynh72.brownie.core.connector.ProviderMisconfiguredException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokens;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google's authorization server stood in by WireMock over real HTTP: what
 * Brownie sends, and how each kind of answer is read.
 */
@ExtendWith(OutputCaptureExtension.class)
class GoogleOAuthClientTest {

    private static final String CLIENT_SECRET = "stand-in-client-secret-value";
    private static final String REFRESH_TOKEN = "1//stand-in-refresh-token-value";
    private static final String ACCESS_TOKEN = "ya29.stand-in-access-token-value";

    private WireMockServer google;
    private GoogleOAuthClient client;

    @BeforeEach
    void startGoogle() {
        google = new WireMockServer(0);
        google.start();
        client = clientWithReadTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void stopGoogle() {
        google.stop();
    }

    @Test
    void theCodeExchangeSendsExactlyWhatGoogleNeedsAndReadsTheGrantedScopes(CapturedOutput output) {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"refresh_token":"%s","token_type":"Bearer",
                 "scope":"https://www.googleapis.com/auth/drive.file  openid"}
                """.formatted(ACCESS_TOKEN, REFRESH_TOKEN))));

        ProviderTokens tokens = client.exchange(ConnectorAccess.DRIVE_FILES, "4/code-value", "verifier-value");

        assertThat(tokens.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(tokens.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(tokens.grantedScopes()).containsExactlyInAnyOrder("https://www.googleapis.com/auth/drive.file", "openid");
        google.verify(postRequestedFor(urlPathEqualTo("/token"))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withFormParam("grant_type", equalTo("authorization_code"))
                .withFormParam("code", equalTo("4/code-value"))
                .withFormParam("code_verifier", equalTo("verifier-value"))
                .withFormParam("redirect_uri", equalTo("http://localhost:8081/api/v1/connectors/google/callback"))
                .withFormParam("client_id", equalTo("client-123"))
                .withFormParam("client_secret", equalTo(CLIENT_SECRET)));
        assertThat(tokens.toString()).doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN);
        assertThat(output.getAll()).doesNotContain(ACCESS_TOKEN).doesNotContain(REFRESH_TOKEN).doesNotContain(CLIENT_SECRET);
    }

    @Test
    void aRefreshSendsTheStoredTokenAndCarriesNoNewOne() {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"token_type":"Bearer","scope":"openid https://www.googleapis.com/auth/calendar.events.owned.readonly"}
                """.formatted(ACCESS_TOKEN))));

        ProviderTokens tokens = client.refresh(ConnectorAccess.CALENDAR_EVENTS, REFRESH_TOKEN);

        assertThat(tokens.refreshToken()).isNull();
        assertThat(tokens.grantedScopes()).contains(GoogleScopes.CALENDAR_OWNED_EVENTS_READ);
        google.verify(postRequestedFor(urlPathEqualTo("/token"))
                .withFormParam("grant_type", equalTo("refresh_token"))
                .withFormParam("refresh_token", equalTo(REFRESH_TOKEN)));
    }

    @Test
    void invalidGrantIsARejectionAndNothingGoogleSaidAboutItIsLogged(CapturedOutput output) {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked for " + REFRESH_TOKEN + "\"}")));

        assertThatThrownBy(() -> client.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN))
                .isInstanceOf(ProviderTokenRejectedException.class)
                .hasMessageNotContaining(REFRESH_TOKEN);
        assertThat(output.getAll())
                .contains("Google refused the refresh (invalid_grant)")
                .doesNotContain(REFRESH_TOKEN)
                .doesNotContain("expired or revoked");
    }

    @Test
    void aRefusalOfBrowniesOwnCredentialsIsMisconfigurationWithOnlyGooglesCodeLogged(CapturedOutput output) {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_client\",\"error_description\":\"The OAuth client was not found.\"}")));

        assertThatThrownBy(() -> client.exchange(ConnectorAccess.DRIVE_FILES, "code", "verifier"))
                .isInstanceOf(ProviderMisconfiguredException.class)
                .hasMessageContaining("invalid_client");
        assertThat(output.getAll()).contains("invalid_client").doesNotContain("was not found");
    }

    @Test
    void anErrorCodeThatIsNotAPlainWordIsNotRepeated(CapturedOutput output) {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"<script>alert(1)</script>\"}")));

        assertThatThrownBy(() -> client.exchange(ConnectorAccess.DRIVE_FILES, "code", "verifier"))
                .isInstanceOf(ProviderMisconfiguredException.class)
                .hasMessageNotContaining("script");
        assertThat(output.getAll()).doesNotContain("<script>");
    }

    @Test
    void throttlingAServerErrorOrNoAnswerAtAllIsUnavailable() throws Exception {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN)).isInstanceOf(ProviderUnavailableException.class);

        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> client.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN)).isInstanceOf(ProviderUnavailableException.class);

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        GoogleOAuthClient nowhere = new GoogleOAuthClient(settings("http://127.0.0.1:" + closedPort), http(Duration.ofSeconds(5)));
        assertThatThrownBy(() -> nowhere.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageNotContaining("127.0.0.1");
    }

    /** The deadline is real: an answer slower than the read timeout is given up on, not waited for. */
    @Test
    void anAnswerSlowerThanTheDeadlineIsGivenUpOn() {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("{\"access_token\":\"late\"}").withFixedDelay(4000)));
        GoogleOAuthClient impatient = clientWithReadTimeout(Duration.ofMillis(500));

        long started = System.nanoTime();
        assertThatThrownBy(() -> impatient.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN)).isInstanceOf(ProviderUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aRedirectIsNotFollowedAndAnOversizedOrNonJsonAnswerIsNotTrusted() {
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(302).withHeader("Location", google.baseUrl() + "/elsewhere")));
        google.stubFor(post(urlPathEqualTo("/elsewhere")).willReturn(okJson("{\"access_token\":\"from-elsewhere\"}")));
        assertThatThrownBy(() -> client.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN)).isInstanceOf(ProviderMisconfiguredException.class);
        google.verify(0, postRequestedFor(urlPathEqualTo("/elsewhere")));

        google.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("{\"access_token\":\"" + "a".repeat(70_000) + "\"}")));
        assertThatThrownBy(() -> client.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN)).isInstanceOf(ProviderUnavailableException.class);

        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(200).withBody("<html>not json</html>")));
        assertThatThrownBy(() -> client.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN)).isInstanceOf(ProviderUnavailableException.class);

        google.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("{\"token_type\":\"Bearer\"}")));
        assertThatThrownBy(() -> client.refresh(ConnectorAccess.DRIVE_FILES, REFRESH_TOKEN))
                .as("an answer with no access token is not a token")
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void driveSaysWhoseTokenItIsAndCalendarUsesTheSignInScopes() {
        google.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .withQueryParam("fields", equalTo("user(permissionId,emailAddress)"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .willReturn(okJson("{\"user\":{\"permissionId\":\"0123456789\",\"emailAddress\":\"person@example.org\"}}")));
        google.stubFor(get(urlPathEqualTo("/userinfo"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .willReturn(okJson("{\"sub\":\"1098765\",\"email\":\"person@example.org\",\"email_verified\":true}")));

        assertThat(client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .isEqualTo(new ProviderAccount("0123456789", "person@example.org"));
        assertThat(client.describeAccount(ConnectorAccess.CALENDAR_EVENTS, ACCESS_TOKEN))
                .isEqualTo(new ProviderAccount("1098765", "person@example.org"));
    }

    @Test
    void anAccountWithNoIdentifierOrATokenGoogleRefusesIsNotAnAccount(CapturedOutput output) {
        google.stubFor(get(urlPathEqualTo("/userinfo")).willReturn(okJson("{\"email\":\"person@example.org\"}")));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.CALENDAR_EVENTS, ACCESS_TOKEN))
                .isInstanceOf(ProviderUnavailableException.class);

        google.stubFor(get(urlPathEqualTo("/drive/v3/about")).willReturn(aResponse().withStatus(401)));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .isInstanceOf(ProviderTokenRejectedException.class);
        assertThat(output.getAll()).contains("Drive account (HTTP 401)").doesNotContain(ACCESS_TOKEN);
    }

    @Test
    void aRefusalIsSortedByGooglesOwnReasonIntoWhoCanDoSomethingAboutIt(CapturedOutput output) {
        stubDriveAbout(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody("""
                {"error":{"code":403,
                  "message":"Google Drive API has not been used in project 123456789012 before or it is disabled.",
                  "errors":[{"message":"Google Drive API has not been used in project 123456789012 before or it is disabled.",
                             "domain":"usageLimits","reason":"accessNotConfigured","extendedHelp":"https://console.developers.google.com"}],
                  "status":"PERMISSION_DENIED",
                  "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SERVICE_DISABLED","domain":"googleapis.com",
                              "metadata":{"service":"drive.googleapis.com","consumer":"projects/123456789012"}}]}}
                """));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .as("an API the project never enabled is the project's setup, not a consent that expired")
                .isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll())
                .contains("Drive account (HTTP 403, accessNotConfigured, SERVICE_DISABLED, PERMISSION_DENIED)")
                .doesNotContain("has not been used in project")
                .doesNotContain(ACCESS_TOKEN);

        stubDriveAbout(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody(
                "{\"error\":{\"code\":403,\"message\":\"Rate Limit Exceeded\",\"errors\":[{\"domain\":\"usageLimits\",\"reason\":\"userRateLimitExceeded\"}]}}"));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .as("a rate limit passes")
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(output.getAll()).contains("Drive account (HTTP 403, userRateLimitExceeded)");

        stubDriveAbout(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody(
                "{\"error\":{\"code\":403,\"errors\":[{\"domain\":\"usageLimits\",\"reason\":\"dailyLimitExceeded\"}]}}"));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .as("a daily cap the project's owner set does not lift by waiting")
                .isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll()).contains("Drive account (HTTP 403, dailyLimitExceeded)");

        stubDriveAbout(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody("""
                {"error":{"errors":[{"domain":"global","reason":"domainPolicy","message":"The domain administrators have disabled Drive apps."}],
                  "code":403,"message":"The domain administrators have disabled Drive apps."}}
                """));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .as("only the account's own administrator can allow the app")
                .isInstanceOf(ConnectorBlockedByOrganizationException.class);
        assertThat(output.getAll()).contains("Drive account (HTTP 403, domainPolicy)").doesNotContain("have disabled Drive apps");

        stubDriveAbout(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody("""
                {"error":{"code":403,"message":"Request had insufficient authentication scopes.",
                  "errors":[{"message":"Insufficient Permission","domain":"global","reason":"insufficientPermissions"}],
                  "status":"PERMISSION_DENIED",
                  "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}
                """));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .as("the exchange had already named every permission asked for, so a lookup refused for one is setup")
                .isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll()).contains("Drive account (HTTP 403, insufficientPermissions, ACCESS_TOKEN_SCOPE_INSUFFICIENT, PERMISSION_DENIED)");

        stubDriveAbout(aResponse().withStatus(403));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll()).contains("Drive account (HTTP 403, no reason given)");

        stubDriveAbout(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody(
                "{\"error\":{\"errors\":[{\"reason\":\"<img src=x>\"}],\"status\":\"rateLimitExceeded because\"}}"));
        assertThatThrownBy(() -> client.describeAccount(ConnectorAccess.DRIVE_FILES, ACCESS_TOKEN))
                .as("text that is not a plain word is neither a reason nor repeated")
                .isInstanceOf(ProviderMisconfiguredException.class);
        assertThat(output.getAll()).doesNotContain("<img").doesNotContain("because");
    }

    @Test
    void revokingSucceedsWhenGoogleForgetsTheTokenOrAlreadyDidNotKnowIt() {
        google.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(200)));
        client.revoke(REFRESH_TOKEN);
        google.verify(postRequestedFor(urlPathEqualTo("/revoke")).withFormParam("token", equalTo(REFRESH_TOKEN)));

        google.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_token\"}")));
        client.revoke(REFRESH_TOKEN);

        google.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> client.revoke(REFRESH_TOKEN)).isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void theScopesRequiredAreTheOnesEachAccessCannotWorkWithout() {
        assertThat(client.requiredScopes(ConnectorAccess.DRIVE_FILES)).isEqualTo(Set.of(GoogleScopes.DRIVE_FILE));
        assertThat(client.requiredScopes(ConnectorAccess.CALENDAR_EVENTS)).isEqualTo(Set.of("openid", GoogleScopes.CALENDAR_OWNED_EVENTS_READ));
        google.verify(0, getRequestedFor(urlPathEqualTo("/userinfo")));
    }

    private void stubDriveAbout(ResponseDefinitionBuilder answer) {
        google.stubFor(get(urlPathEqualTo("/drive/v3/about")).willReturn(answer));
    }

    private GoogleOAuthClient clientWithReadTimeout(Duration readTimeout) {
        return new GoogleOAuthClient(settings(google.baseUrl()), http(readTimeout));
    }

    private static GoogleHttp http(Duration readTimeout) {
        return GoogleHttp.create(Duration.ofSeconds(2), readTimeout, new ObjectMapper());
    }

    private static GoogleClientSettings settings(String base) {
        return new GoogleClientSettings(
                "client-123",
                CLIENT_SECRET,
                URI.create("http://localhost:8081/api/v1/connectors/google/callback"),
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create(base + "/token"),
                URI.create(base + "/revoke"),
                URI.create(base + "/userinfo"),
                URI.create(base),
                "https://accounts.google.com",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }
}
