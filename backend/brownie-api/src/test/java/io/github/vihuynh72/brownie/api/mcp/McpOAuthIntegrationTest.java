package io.github.vihuynh72.brownie.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OAuth 2.1 authorization server end to end, on real Postgres: open
 * registration, an anonymous authorize bouncing to sign-in, the signed-in
 * authorize landing on consent, Allow and Deny, the PKCE exchange,
 * {@code /mcp} accepting what the exchange minted, rotation, and
 * revocation. The issuer everything names is the fixed configured public
 * origin, {@code http://localhost:8081} in this profile -- deliberately
 * not the random test port, so a test that wants the resource names it
 * exactly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class McpOAuthIntegrationTest extends McpIntegrationTestSupport {

    private static final String ISSUER = "https://issuer-mcp-oauth";
    private static final String ORIGIN = "http://localhost:8081";
    private static final String RESOURCE = ORIGIN + "/mcp";
    private static final String REDIRECT = "http://localhost:19999/cb";

    private static final String AUTHORIZE = "/mcp-auth/oauth2/v1/authorize";
    private static final String TOKEN = "/mcp-auth/oauth2/v1/token";
    private static final String REGISTER = "/mcp-auth/oauth2/v1/register";
    private static final String REVOKE = "/mcp-auth/oauth2/v1/revoke";
    private static final String METADATA = "/.well-known/oauth-authorization-server";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, DB);
    }

    @Test
    void theWholeLoopWorksForAPublicClient() throws Exception {
        Browser browser = signIn("subject-oauth-public", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();

        // Anonymous at authorize: bounced to the app's own sign-in with
        // the pending request stashed under a fresh session.
        String authorizeUrl = authorizeQuery(clientId, pkce().challenge, "client-state-1", "read fill export");
        HttpResponse<String> anonymous = http.send(
                HttpRequest.newBuilder(URI.create(url(authorizeUrl))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(302);
        assertThat(anonymous.headers().firstValue("Location").orElseThrow())
                .endsWith("/oauth2/authorization/entra");
        assertThat(anonymous.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("SESSION="));

        // Signed in, the same request lands on the consent page instead.
        Pkce pkce = pkce();
        String consented = authorizeQuery(clientId, pkce.challenge, "client-state-1", "read fill export");
        HttpResponse<String> authorize = getRaw(browser, consented);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String consentLocation = authorize.headers().firstValue("Location").orElseThrow();
        assertThat(consentLocation).contains("/mcp-auth/consent");
        Map<String, String> consentParams = queryParams(consentLocation);
        assertThat(consentParams.get("client_id")).isEqualTo(clientId);
        assertThat(consentParams.get("state")).isNotBlank();

        HttpResponse<String> consentPage = getRaw(browser, URI.create(consentLocation).getRawPath()
                + "?" + URI.create(consentLocation).getRawQuery());
        assertThat(consentPage.statusCode()).isEqualTo(200);
        assertThat(consentPage.body()).contains("Chat Client");

        // Allow is the page's own form, submitted exactly as rendered.
        HttpResponse<String> allowed = submitRenderedConsent(browser, consentPage);
        String code = codeFrom(allowed);
        assertThat(queryParams(allowed.headers().firstValue("Location").orElseThrow()).get("state"))
                .isEqualTo("client-state-1");

        JsonNode tokens = tokenExchange(clientId, null, code, pkce.verifier);
        String accessToken = tokens.get("access_token").asText();
        String refreshToken = tokens.get("refresh_token").asText();

        assertThat(mcpInitialize(accessToken).statusCode()).isEqualTo(200);
        JsonNode templates = readJson(mcpCall(accessToken,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_templates\",\"arguments\":{}}}"));
        assertThat(templates.get("result")).isNotNull();

        JsonNode rotated = refresh(clientId, null, refreshToken);
        String newAccess = rotated.get("access_token").asText();
        String newRefresh = rotated.get("refresh_token").asText();
        assertThat(newRefresh).isNotEqualTo(refreshToken);
        // The rotated-away refresh token is dead at once.
        HttpResponse<String> reusedRefresh = tokenForm(clientId, null,
                "grant_type=refresh_token&refresh_token=" + encode(refreshToken) + "&client_id=" + clientId);
        assertThat(reusedRefresh.statusCode()).isEqualTo(400);
        assertThat(readJson(reusedRefresh).get("error").asText()).isEqualTo("invalid_grant");

        assertThat(revoke(clientId, null, newAccess).statusCode()).isEqualTo(200);
        assertThat(mcpInitialize(newAccess).statusCode()).isEqualTo(401);

        // The browser session that ran the consent is still the OIDC one.
        JsonNode me = readJson(get(browser, "/api/v1/me", 200));
        assertThat(me.get("userId").asLong()).isEqualTo(browser.userId());
    }

    @Test
    void theWholeLoopWorksForAConfidentialClient() throws Exception {
        Browser browser = signIn("subject-oauth-confidential", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "client_secret_basic"), 201);
        String clientId = registration.get("client_id").asText();
        String secret = registration.get("client_secret").asText();
        assertThat(secret).isNotBlank();

        Pkce pkce = pkce();
        String consented = authorizeQuery(clientId, pkce.challenge, "state-c", "read");
        String consentLocation =
                getRaw(browser, consented).headers().firstValue("Location").orElseThrow();
        URI consent = URI.create(consentLocation);
        HttpResponse<String> consentPage =
                getRaw(browser, consent.getRawPath() + "?" + consent.getRawQuery());
        HttpResponse<String> allowed = submitRenderedConsent(browser, consentPage);
        String code = codeFrom(allowed);

        JsonNode tokens = tokenExchange(clientId, secret, code, pkce.verifier);
        String accessToken = tokens.get("access_token").asText();
        assertThat(mcpInitialize(accessToken).statusCode()).isEqualTo(200);

        // A confidential client that sends no credentials is refused.
        HttpResponse<String> unauthenticatedRefresh = tokenForm(clientId, null,
                "grant_type=refresh_token&refresh_token=" + encode(tokens.get("refresh_token").asText()));
        assertThat(unauthenticatedRefresh.statusCode()).isIn(400, 401);

        JsonNode rotated = refresh(clientId, secret, tokens.get("refresh_token").asText());
        assertThat(rotated.get("access_token").asText()).isNotBlank();
        assertThat(revoke(clientId, secret, rotated.get("access_token").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void consentPageListsEachRequestedScopeAndSubmitsThemAsSeparateFields() throws Exception {
        Browser browser = signIn("subject-oauth-scope-list", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Pkce pkce = pkce();
        String consentLocation = getRaw(browser,
                authorizeQuery(clientId, pkce.challenge, "scope-state", "read fill export"))
                .headers().firstValue("Location").orElseThrow();
        URI consent = URI.create(consentLocation);
        HttpResponse<String> page =
                getRaw(browser, consent.getRawPath() + "?" + consent.getRawQuery());
        assertThat(page.statusCode()).isEqualTo(200);

        // Each requested scope is its own described row and its own hidden
        // field, so the rendered form submits them back as separate values.
        assertThat(page.body())
                .contains("<li><strong>read</strong>")
                .contains("<li><strong>fill</strong>")
                .contains("<li><strong>export</strong>")
                .doesNotContain("does not describe");
        assertThat(Pattern.compile("name=\"scope\"").matcher(page.body()).results().count())
                .isEqualTo(3);

        String code = codeFrom(submitRenderedConsent(browser, page));
        JsonNode tokens = tokenExchange(clientId, null, code, pkce.verifier);
        assertThat(tokens.get("scope").asText().split(" "))
                .containsExactlyInAnyOrder("read", "fill", "export");
    }

    @Test
    void anAbsentAuthMethodRegistersAsClientSecretBasic() throws Exception {
        String body = "{\"redirect_uris\":[\"" + REDIRECT + "\"],\"client_name\":\"Default Method\"}";
        HttpResponse<String> response = postRaw(REGISTER, body);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode registration = readJson(response);
        // RFC 7591's default for a silent field is client_secret_basic,
        // and this server does not rewrite what a client chose.
        assertThat(registration.get("token_endpoint_auth_method").asText()).isEqualTo("client_secret_basic");
        assertThat(registration.get("client_secret").asText()).isNotBlank();
    }

    @Test
    void aRegistrationWithAScopeFieldKeepsOnlyOurs() throws Exception {
        String body = "{\"redirect_uris\":[\"" + REDIRECT + "\"],\"client_name\":\"Scoped Chat\","
                + "\"token_endpoint_auth_method\":\"none\",\"scope\":\"read bogus\"}";
        HttpResponse<String> response = postRaw(REGISTER, body);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode registration = readJson(response);

        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT scopes FROM oauth2_registered_client WHERE client_id = ?")) {
            statement.setString(1, registration.get("client_id").asText());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("read");
            }
        }
    }

    @Test
    void aRegistrationWithNoScopeFieldGetsAllThree() throws Exception {
        HttpResponse<String> response = postRaw(REGISTER,
                "{\"redirect_uris\":[\"" + REDIRECT + "\"],\"client_name\":\"All Scopes\","
                        + "\"token_endpoint_auth_method\":\"none\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode registration = readJson(response);
        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT scopes FROM oauth2_registered_client WHERE client_id = ?")) {
            statement.setString(1, registration.get("client_id").asText());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1).split(",")).containsExactlyInAnyOrder("read", "fill", "export");
            }
        }
    }

    @Test
    void aBadRedirectUriIsRefusedAtRegistration() throws Exception {
        assertThat(postRaw(REGISTER,
                "{\"redirect_uris\":[\"http://example.com/cb\"],\"client_name\":\"Plain Http\","
                        + "\"token_endpoint_auth_method\":\"none\"}").statusCode())
                .isEqualTo(400);
        assertThat(postRaw(REGISTER,
                "{\"redirect_uris\":[\"javascript:alert(1)\"],\"client_name\":\"Scheme\","
                        + "\"token_endpoint_auth_method\":\"none\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void registrationAcceptsOnlyHttpsOrLoopbackHttpRedirects() throws Exception {
        for (String refused : List.of(
                "cursor://anysphere.cursor-mcp/oauth/callback",
                "http://example.com/callback",
                "javascript:alert(1)")) {
            HttpResponse<String> response = postRaw(REGISTER,
                    "{\"redirect_uris\":[\"" + refused + "\"],\"client_name\":\"Rejected Redirect\","
                            + "\"token_endpoint_auth_method\":\"none\"}");
            assertThat(response.statusCode()).as("%s -> %s", refused, response.body()).isEqualTo(400);
            assertThat(readJson(response).get("error").asText())
                    .as(refused).isEqualTo("invalid_redirect_uri");
        }
        for (String accepted : List.of(
                "https://chatgpt.com/connector/oauth/abc",
                "http://127.0.0.1:33418/callback")) {
            HttpResponse<String> response = postRaw(REGISTER,
                    "{\"redirect_uris\":[\"" + accepted + "\"],\"client_name\":\"Accepted Redirect\","
                            + "\"token_endpoint_auth_method\":\"none\"}");
            assertThat(response.statusCode()).as("%s -> %s", accepted, response.body()).isEqualTo(201);
        }
    }

    @Test
    void anUnsupportedAuthMethodIsRefused() throws Exception {
        HttpResponse<String> response = postRaw(REGISTER,
                "{\"redirect_uris\":[\"" + REDIRECT + "\"],\"client_name\":\"Private Key\","
                        + "\"token_endpoint_auth_method\":\"private_key_jwt\"}");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(readJson(response).get("error").asText()).isEqualTo("invalid_client_metadata");
    }

    @Test
    void authorizeRefusesMissingAndPlainPkce() throws Exception {
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();

        HttpResponse<String> missing = http.send(HttpRequest.newBuilder(URI.create(url(
                AUTHORIZE + "?response_type=code&client_id=" + clientId + "&redirect_uri=" + encode(REDIRECT)
                        + "&resource=" + encode(RESOURCE) + "&scope=read"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(errorAtClient(missing)).isEqualTo("invalid_request");

        HttpResponse<String> plain = http.send(HttpRequest.newBuilder(URI.create(url(
                AUTHORIZE + "?response_type=code&client_id=" + clientId + "&redirect_uri=" + encode(REDIRECT)
                        + "&resource=" + encode(RESOURCE) + "&scope=read"
                        + "&code_challenge=abc&code_challenge_method=plain"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(errorAtClient(plain)).isEqualTo("invalid_request");
    }

    @Test
    void authorizeWithoutScopeIsRefusedWithInvalidScope() throws Exception {
        Browser browser = signIn("subject-oauth-no-scope", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();

        // A signed-in request that asks for no scope is answered to the
        // client as invalid_scope -- it never reaches the consent page.
        HttpResponse<String> response = getRaw(browser,
                AUTHORIZE + "?response_type=code&client_id=" + clientId
                        + "&redirect_uri=" + encode(REDIRECT)
                        + "&state=no-scope-state"
                        + "&resource=" + encode(RESOURCE)
                        + "&code_challenge=" + pkce().challenge
                        + "&code_challenge_method=S256");
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(REDIRECT);
        Map<String, String> back = queryParams(location);
        assertThat(back.get("error")).isEqualTo("invalid_scope");
        assertThat(back.get("state")).isEqualTo("no-scope-state");
    }

    @Test
    void authorizeRefusesAResourceThatIsNotOurs() throws Exception {
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url(
                authorizeQuery(clientId, pkce().challenge, "s", "read")
                        .replace(encode(RESOURCE), encode("https://other.example/mcp")))))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(errorAtClient(response)).isEqualTo("invalid_target");
    }

    @Test
    void theTokenEndpointRefusesAResourceThatIsNotOurs() throws Exception {
        Browser browser = signIn("subject-oauth-bad-resource", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Pkce pkce = pkce();
        Map<String, String> consentParams = queryParams(getRaw(browser,
                authorizeQuery(clientId, pkce.challenge, "s2", "read"))
                .headers().firstValue("Location").orElseThrow());
        String code = codeFrom(postConsentAllow(browser, clientId, consentParams.get("state"), List.of("read")));

        HttpResponse<String> refused = tokenForm(clientId, null,
                "grant_type=authorization_code&code=" + encode(code) + "&redirect_uri=" + encode(REDIRECT)
                        + "&code_verifier=" + encode(pkce.verifier) + "&client_id=" + clientId
                        + "&resource=" + encode("https://other.example/mcp"));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(readJson(refused).get("error").asText()).isEqualTo("invalid_target");
    }

    @Test
    void denyAnswersAccessDenied() throws Exception {
        Browser browser = signIn("subject-oauth-deny", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Map<String, String> consentParams = queryParams(getRaw(browser,
                authorizeQuery(clientId, pkce().challenge, "deny-state", "read"))
                .headers().firstValue("Location").orElseThrow());

        // Deny is the consent POST without any scope grant; the server
        // answers the client with access_denied, not a code.
        HttpResponse<String> denied = postForm(browser, AUTHORIZE, Map.of(
                "client_id", clientId, "state", consentParams.get("state")));
        assertThat(denied.statusCode()).isEqualTo(302);
        Map<String, String> back = queryParams(denied.headers().firstValue("Location").orElseThrow());
        assertThat(back.get("error")).isEqualTo("access_denied");
        assertThat(back.get("state")).isEqualTo("deny-state");
    }

    @Test
    void consentStateForgeryIsRefused() throws Exception {
        Browser owner = signIn("subject-oauth-consent-owner", ISSUER);
        Browser other = signIn("subject-oauth-consent-other", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Map<String, String> consentParams = queryParams(getRaw(owner,
                authorizeQuery(clientId, pkce().challenge, "s3", "read"))
                .headers().firstValue("Location").orElseThrow());
        String realState = consentParams.get("state");

        // Missing state.
        HttpResponse<String> missing = postForm(owner, AUTHORIZE, Map.of("client_id", clientId));
        assertThat(refusedWithoutCode(missing)).as("missing state").isTrue();

        // A state that names no pending authorization.
        HttpResponse<String> wrong = postForm(owner, AUTHORIZE, Map.of(
                "client_id", clientId, "state", "forged-state-value"));
        assertThat(refusedWithoutCode(wrong)).as("forged state").isTrue();

        // Another person's session cannot spend this one's state: the
        // consent provider matches the principal, not just the value.
        HttpResponse<String> stolen = postForm(other, AUTHORIZE, Map.of(
                "client_id", clientId, "state", realState));
        assertThat(refusedWithoutCode(stolen)).as("another user's state").isTrue();
    }

    @Test
    void consentPageCarriesItsOwnContentSecurityPolicy() throws Exception {
        Browser browser = signIn("subject-oauth-csp", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        String consentLocation = getRaw(browser,
                authorizeQuery(clientId, pkce().challenge, "csp-state", "read"))
                .headers().firstValue("Location").orElseThrow();
        URI consent = URI.create(consentLocation);
        HttpResponse<String> page =
                getRaw(browser, consent.getRawPath() + "?" + consent.getRawQuery());
        assertThat(page.statusCode()).isEqualTo(200);

        // The page's own policy: no form-action (Allow's answer is a redirect
        // to the chat app's origin), a nonce that matches the style block's,
        // and no inline style attributes.
        String csp = page.headers().firstValue("Content-Security-Policy").orElseThrow();
        Matcher nonce = Pattern.compile(
                "^default-src 'none'; style-src 'nonce-([A-Za-z0-9+/=]+)'; frame-ancestors 'none'; base-uri 'none'$")
                .matcher(csp);
        assertThat(nonce.matches()).as("CSP header: %s", csp).isTrue();
        assertThat(csp).doesNotContain("form-action");
        assertThat(page.body()).contains("<style nonce=\"" + nonce.group(1) + "\">");
        assertThat(page.body()).doesNotContain("style=\"");
    }

    @Test
    void aHostileClientNameIsEscapedOnTheConsentPage() throws Exception {
        Browser browser = signIn("subject-oauth-escape", ISSUER);
        String hostile = "\"><script>alert(document.cookie)</script>";
        JsonNode registration = register(Map.of("client_name", hostile, "token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        String consentLocation = getRaw(browser, authorizeQuery(clientId, pkce().challenge, "s4", "read"))
                .headers().firstValue("Location").orElseThrow();
        URI consent = URI.create(consentLocation);

        HttpResponse<String> page = getRaw(browser, consent.getRawPath() + "?" + consent.getRawQuery());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).doesNotContain("<script>");
        assertThat(page.body()).contains("&lt;script&gt;");
    }

    @Test
    void theMetadataDocumentAdvertisesOnlyWhatThisServerAnswers() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url(METADATA))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode metadata = readJson(response);
        assertThat(metadata.get("issuer").asText()).isEqualTo(ORIGIN);
        assertThat(metadata.get("authorization_endpoint").asText()).isEqualTo(ORIGIN + AUTHORIZE);
        assertThat(metadata.get("token_endpoint").asText()).isEqualTo(ORIGIN + TOKEN);
        assertThat(metadata.get("registration_endpoint").asText()).isEqualTo(ORIGIN + REGISTER);
        assertThat(metadata.get("revocation_endpoint").asText()).isEqualTo(ORIGIN + REVOKE);
        assertThat(metadata.get("grant_types_supported"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrder("authorization_code", "refresh_token");
        assertThat(metadata.get("token_endpoint_auth_methods_supported"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrder("none", "client_secret_basic", "client_secret_post");
        assertThat(metadata.get("code_challenge_methods_supported"))
                .extracting(JsonNode::asText).containsExactly("S256");
        assertThat(metadata.get("scopes_supported"))
                .extracting(JsonNode::asText).containsExactlyInAnyOrder("read", "fill", "export");
        // Endpoints this server does not run are not advertised.
        assertThat(metadata.has("introspection_endpoint")).isFalse();
        assertThat(metadata.has("pushed_authorization_request_endpoint")).isFalse();
        assertThat(metadata.has("device_authorization_endpoint")).isFalse();
    }

    @Test
    void noRawCodeTokenOrStateValueLandsInTheAuthorizationTable() throws Exception {
        Browser browser = signIn("subject-oauth-storage", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Pkce pkce = pkce();
        Map<String, String> consentParams = queryParams(getRaw(browser,
                authorizeQuery(clientId, pkce.challenge, "s5", "read"))
                .headers().firstValue("Location").orElseThrow());
        String code = codeFrom(postConsentAllow(browser, clientId, consentParams.get("state"), List.of("read")));
        JsonNode tokens = tokenExchange(clientId, null, code, pkce.verifier);
        String accessToken = tokens.get("access_token").asText();
        String refreshToken = tokens.get("refresh_token").asText();
        List<String> rawSecrets = List.of(code, accessToken, refreshToken, consentParams.get("state"));

        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT authorization_code_value, access_token_value, refresh_token_value, state"
                                + " FROM oauth2_authorization WHERE registered_client_id = ?")) {
            statement.setString(1, registeredClientId(clientId));
            int rows = 0;
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    for (int column = 1; column <= 4; column++) {
                        String value = rs.getString(column);
                        if (value == null) {
                            continue;
                        }
                        assertThat(value).as("column " + column).startsWith("h1:");
                        assertThat(rawSecrets).doesNotContain(value);
                    }
                }
            }
            assertThat(rows).isGreaterThan(0);
        }
    }

    @Test
    void revokingTheAccessTokenLeavesTheRefreshTokenUsable() throws Exception {
        Browser browser = signIn("subject-oauth-partial-revoke", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Pkce pkce = pkce();
        Map<String, String> consentParams = queryParams(getRaw(browser,
                authorizeQuery(clientId, pkce.challenge, "s6", "read"))
                .headers().firstValue("Location").orElseThrow());
        String code = codeFrom(postConsentAllow(browser, clientId, consentParams.get("state"), List.of("read")));
        JsonNode tokens = tokenExchange(clientId, null, code, pkce.verifier);

        assertThat(revoke(clientId, null, tokens.get("access_token").asText()).statusCode()).isEqualTo(200);
        assertThat(mcpInitialize(tokens.get("access_token").asText()).statusCode()).isEqualTo(401);

        JsonNode refreshed = refresh(clientId, null, tokens.get("refresh_token").asText());
        assertThat(mcpInitialize(refreshed.get("access_token").asText()).statusCode()).isEqualTo(200);
    }

    @Test
    void revokingTheGrantOnOurSideKillsBothTokensAtOnce() throws Exception {
        Browser browser = signIn("subject-oauth-grant-revoke", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Pkce pkce = pkce();
        Map<String, String> consentParams = queryParams(getRaw(browser,
                authorizeQuery(clientId, pkce.challenge, "s7", "read"))
                .headers().firstValue("Location").orElseThrow());
        String code = codeFrom(postConsentAllow(browser, clientId, consentParams.get("state"), List.of("read")));
        JsonNode tokens = tokenExchange(clientId, null, code, pkce.verifier);
        String accessToken = tokens.get("access_token").asText();
        String refreshToken = tokens.get("refresh_token").asText();
        assertThat(mcpInitialize(accessToken).statusCode()).isEqualTo(200);

        JsonNode grants = readJson(get(browser,
                "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/grants", 200));
        long grantId = -1;
        for (JsonNode grant : grants) {
            if (grant.get("kind").asText().equals("OAUTH_CLIENT")) {
                grantId = grant.get("id").asLong();
            }
        }
        assertThat(grantId).as("an OAUTH_CLIENT grant was recorded").isPositive();
        postJson(browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/grants/" + grantId + "/revoke", null, 204);

        assertThat(mcpInitialize(accessToken).statusCode()).isEqualTo(401);
        HttpResponse<String> refresh = tokenForm(clientId, null,
                "grant_type=refresh_token&refresh_token=" + encode(refreshToken) + "&client_id=" + clientId);
        assertThat(refresh.statusCode()).isEqualTo(400);
        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?")) {
            statement.setString(1, "user:" + browser.userId());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    void aWorkspacePurgeRemovesThePersonsAuthorizations() throws Exception {
        Browser browser = signIn("subject-oauth-purge", ISSUER);
        JsonNode registration = register(Map.of("token_endpoint_auth_method", "none"), 201);
        String clientId = registration.get("client_id").asText();
        Pkce pkce = pkce();
        Map<String, String> consentParams = queryParams(getRaw(browser,
                authorizeQuery(clientId, pkce.challenge, "s8", "read"))
                .headers().firstValue("Location").orElseThrow());
        codeFrom(postConsentAllow(browser, clientId, consentParams.get("state"), List.of("read")));

        long principalRows = countAuthorizations(browser.userId());
        assertThat(principalRows).isPositive();

        postJson(browser, "/api/v1/workspaces/" + browser.workspaceId() + "/deletions",
                "{\"scope\":\"WORKSPACE\"}", 200);
        assertThat(countAuthorizations(browser.userId())).isZero();
    }

    // -- plumbing ---------------------------------------------------------------

    private long countAuthorizations(long userId) throws Exception {
        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?")) {
            statement.setString(1, "user:" + userId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private String registeredClientId(String clientId) throws Exception {
        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM oauth2_registered_client WHERE client_id = ?")) {
            statement.setString(1, clientId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private JsonNode register(Map<String, String> extraFields, int expectedStatus) throws Exception {
        StringBuilder body = new StringBuilder("{\"redirect_uris\":[\"" + REDIRECT + "\"],\"client_name\":\"Chat Client\"");
        extraFields.forEach((key, value) -> body.append(",\"").append(key).append("\":\"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append("\""));
        body.append("}");
        HttpResponse<String> response = postRaw(REGISTER, body.toString());
        assertThat(response.statusCode()).as("DCR -> %s", response.body()).isEqualTo(expectedStatus);
        return readJson(response);
    }

    private HttpResponse<String> postRaw(String path, String jsonBody) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String authorizeQuery(String clientId, String challenge, String state, String scope) {
        return AUTHORIZE + "?response_type=code&client_id=" + clientId
                + "&redirect_uri=" + encode(REDIRECT)
                + "&scope=" + encode(scope)
                + "&state=" + encode(state)
                + "&resource=" + encode(RESOURCE)
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";
    }

    private HttpResponse<String> getRaw(Browser browser, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Cookie", "SESSION=" + browser.sessionCookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * What a browser does with the consent page: submits the Allow form
     * exactly as rendered -- its action, and every hidden field it carried.
     */
    private HttpResponse<String> submitRenderedConsent(Browser browser, HttpResponse<String> consentPage)
            throws Exception {
        assertThat(consentPage.statusCode()).isEqualTo(200);
        String allowForm = null;
        for (String segment : consentPage.body().split("<form")) {
            if (segment.contains("class=\"allow\"")) {
                allowForm = "<form" + segment;
            }
        }
        assertThat(allowForm).as("an Allow form on the consent page").isNotNull();
        Matcher action = Pattern.compile("action=\"([^\"]*)\"").matcher(allowForm);
        assertThat(action.find()).isTrue();
        Map<String, List<String>> fields = new LinkedHashMap<>();
        Matcher inputs = Pattern.compile("<input type=\"hidden\" name=\"([^\"]*)\" value=\"([^\"]*)\">")
                .matcher(allowForm);
        while (inputs.find()) {
            fields.computeIfAbsent(unescapeHtml(inputs.group(1)), key -> new ArrayList<>())
                    .add(unescapeHtml(inputs.group(2)));
        }
        return postFormMulti(browser, unescapeHtml(action.group(1)), fields);
    }

    /** The five entities the consent page's escaping can produce, undone in one pass. */
    private static String unescapeHtml(String value) {
        Matcher entities = Pattern.compile("&(?:amp|lt|gt|quot|#39);").matcher(value);
        StringBuilder out = new StringBuilder();
        while (entities.find()) {
            String unescaped = switch (entities.group()) {
                case "&amp;" -> "&";
                case "&lt;" -> "<";
                case "&gt;" -> ">";
                case "&quot;" -> "\"";
                default -> "'";
            };
            entities.appendReplacement(out, Matcher.quoteReplacement(unescaped));
        }
        entities.appendTail(out);
        return out.toString();
    }

    /** The Allow: one scope parameter per granted scope, the way the consent provider reads them. */
    private HttpResponse<String> postConsentAllow(Browser browser, String clientId, String state, List<String> scopes)
            throws Exception {
        Map<String, List<String>> form = new LinkedHashMap<>();
        form.put("client_id", List.of(clientId));
        form.put("state", List.of(state));
        form.put("scope", scopes);
        return postFormMulti(browser, AUTHORIZE, form);
    }

    private HttpResponse<String> postForm(Browser browser, String path, Map<String, String> fields) throws Exception {
        Map<String, List<String>> multi = new LinkedHashMap<>();
        fields.forEach((key, value) -> multi.put(key, List.of(value)));
        return postFormMulti(browser, path, multi);
    }

    private HttpResponse<String> postFormMulti(Browser browser, String path, Map<String, List<String>> fields)
            throws Exception {
        StringBuilder body = new StringBuilder();
        fields.forEach((key, values) -> values.forEach(value -> {
            if (body.length() > 0) {
                body.append("&");
            }
            body.append(encode(key)).append("=").append(encode(value));
        }));
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Cookie", "SESSION=" + browser.sessionCookie())
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String codeFrom(HttpResponse<String> consentAnswer) {
        assertThat(consentAnswer.statusCode()).as("consent -> %s", consentAnswer.body()).isEqualTo(302);
        String location = consentAnswer.headers().firstValue("Location").orElseThrow();
        String code = queryParams(location).get("code");
        assertThat(code).as("no code in %s", location).isNotBlank();
        return code;
    }

    private JsonNode tokenExchange(String clientId, String secret, String code, String verifier) throws Exception {
        HttpResponse<String> response = tokenForm(clientId, secret,
                "grant_type=authorization_code&code=" + encode(code)
                        + "&redirect_uri=" + encode(REDIRECT)
                        + "&code_verifier=" + encode(verifier)
                        + "&client_id=" + clientId);
        assertThat(response.statusCode()).as("token exchange -> %s", response.body()).isEqualTo(200);
        return readJson(response);
    }

    private JsonNode refresh(String clientId, String secret, String refreshToken) throws Exception {
        HttpResponse<String> response = tokenForm(clientId, secret,
                "grant_type=refresh_token&refresh_token=" + encode(refreshToken) + "&client_id=" + clientId);
        assertThat(response.statusCode()).as("refresh -> %s", response.body()).isEqualTo(200);
        return readJson(response);
    }

    private HttpResponse<String> revoke(String clientId, String secret, String token) throws Exception {
        return tokenForm(clientId, secret, "token=" + encode(token) + "&client_id=" + clientId, REVOKE);
    }

    private HttpResponse<String> tokenForm(String clientId, String secret, String body) throws Exception {
        return tokenForm(clientId, secret, body, TOKEN);
    }

    private HttpResponse<String> tokenForm(String clientId, String secret, String body, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (secret != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> mcpInitialize(String token) throws Exception {
        return mcpCall(token,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
    }

    private HttpResponse<String> mcpCall(String token, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url("/mcp")))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The error code the authorize endpoint handed back to the client redirect, or null when none. */
    private String errorAtClient(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        return queryParams(response.headers().firstValue("Location").orElseThrow()).get("error");
    }

    /** A refused consent: anything that is not a redirect carrying a code. */
    private boolean refusedWithoutCode(HttpResponse<String> response) {
        String location = response.headers().firstValue("Location").orElse("");
        return response.statusCode() != 302 || queryParams(location).get("code") == null;
    }

    private static Map<String, String> queryParams(String location) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = URI.create(location).getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(
                    java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return params;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Pkce pkce() throws Exception {
        String verifier = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().concat(UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8))
                .substring(0, 64);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));
        return new Pkce(verifier, challenge);
    }

    private record Pkce(String verifier, String challenge) {
    }
}
