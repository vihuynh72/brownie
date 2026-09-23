package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorOAuthClient;
import io.github.vihuynh72.brownie.core.connector.ProviderAccount;
import io.github.vihuynh72.brownie.core.connector.ProviderMisconfiguredException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokens;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Google's authorization server, spoken to directly: the code exchange, the
 * refresh, the question of whose account a token is for, and revocation. No
 * Google library is involved, so there is no retry, cache or token store
 * acting behind Brownie's back; each method is one request.
 *
 * <p>What Google answers is sorted into three meanings. Google's own
 * {@code invalid_grant} (a consent code already used or expired, a refresh
 * token revoked or expired), or a 401 for a token just issued, is {@link
 * ProviderTokenRejectedException}. A refusal of Brownie's own credentials or
 * request, including a 403 whose reason is the project's setup (an API not
 * enabled for it, for one), is {@link ProviderMisconfiguredException}. No
 * answer, a throttle (a 429, or a 403 whose reason is a rate limit) or a
 * server error is {@link ProviderUnavailableException}. A 403 saying the
 * account's organization does not allow the app is {@link
 * ConnectorBlockedByOrganizationException}. Error codes and reasons are
 * logged; Google's description, the answer body and every token are not.
 */
public class GoogleOAuthClient implements ConnectorOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);

    private final GoogleClientSettings settings;
    private final GoogleHttp http;

    public GoogleOAuthClient(GoogleClientSettings settings, GoogleHttp http) {
        this.settings = settings;
        this.http = http;
    }

    @Override
    public Set<String> requiredScopes(ConnectorAccess access) {
        return GoogleScopes.required(access);
    }

    @Override
    public ProviderTokens exchange(ConnectorAccess access, String authorizationCode, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        form.add("redirect_uri", settings.redirectUri().toString());
        form.add("client_id", settings.clientId());
        form.add("client_secret", settings.clientSecret());
        form.add("code_verifier", codeVerifier);
        return tokensFrom(postForm(settings.tokenUri(), form, "code exchange"), "code exchange");
    }

    @Override
    public ProviderTokens refresh(ConnectorAccess access, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", settings.clientId());
        form.add("client_secret", settings.clientSecret());
        return tokensFrom(postForm(settings.tokenUri(), form, "refresh"), "refresh");
    }

    /**
     * For Calendar, the sign-in scopes it asks for make Google's user-info
     * endpoint answer. For Drive, the file picker allows no scope but {@code
     * drive.file}, so the account is whoever Drive says the token belongs to.
     */
    @Override
    public ProviderAccount describeAccount(ConnectorAccess access, String accessToken) {
        return switch (access) {
            case DRIVE_FILES -> {
                URI about = UriComponentsBuilder.fromUri(settings.apiBaseUri())
                        .path("/drive/v3/about")
                        .queryParam("fields", "user(permissionId,emailAddress)")
                        .encode()
                        .build()
                        .toUri();
                JsonNode user = http.json(getWithToken(access, about, accessToken, "Drive account")).path("user");
                yield account(GoogleHttp.text(user, "permissionId"), GoogleHttp.text(user, "emailAddress"));
            }
            case CALENDAR_EVENTS -> {
                JsonNode info = http.json(getWithToken(access, settings.userInfoUri(), accessToken, "account"));
                yield account(GoogleHttp.text(info, "sub"), GoogleHttp.text(info, "email"));
            }
        };
    }

    /** Google answers 200 when it revoked the token and 400 when it did not know it, which leaves the same result. */
    @Override
    public void revoke(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        GoogleHttp.Answer answer = send(http.restClient().post()
                .uri(settings.revocationUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form));
        if (answer.isSuccess() || answer.status() == 400) {
            return;
        }
        if (answer.isProviderFailure()) {
            throw new ProviderUnavailableException("Google could not revoke the token right now (HTTP " + answer.status() + ").");
        }
        log.warn("Google refused a revocation request (HTTP {}).", answer.status());
        throw new ProviderMisconfiguredException("Google refused Brownie's revocation request (HTTP " + answer.status() + ").");
    }

    private GoogleHttp.Answer postForm(URI uri, MultiValueMap<String, String> form, String what) {
        GoogleHttp.Answer answer = send(http.restClient().post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form));
        if (answer.isSuccess()) {
            return answer;
        }
        if (answer.isProviderFailure()) {
            throw new ProviderUnavailableException("Google could not complete the " + what + " right now (HTTP " + answer.status() + ").");
        }
        String error = errorCodeOf(answer);
        if ("invalid_grant".equals(error)) {
            log.info("Google refused the {} (invalid_grant).", what);
            throw new ProviderTokenRejectedException("Google no longer accepts this " + ("refresh".equals(what) ? "token." : "consent."));
        }
        log.warn("Google refused Brownie's {} request (HTTP {}, {}).", what, answer.status(), error == null ? "no error code" : error);
        throw new ProviderMisconfiguredException("Google refused Brownie's " + what + " request (" + (error == null ? "HTTP " + answer.status() : error) + ").");
    }

    private GoogleHttp.Answer getWithToken(ConnectorAccess access, URI uri, String accessToken, String what) {
        GoogleHttp.Answer answer = send(http.restClient().get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON));
        if (answer.isSuccess()) {
            return answer;
        }
        throw GoogleApiRefusals.of(http, answer, access, what);
    }

    private GoogleHttp.Answer send(org.springframework.web.client.RestClient.RequestHeadersSpec<?> request) {
        try {
            return http.send(request, GoogleHttp.SMALL_ANSWER_BYTES);
        } catch (GoogleHttp.AnswerTooLargeException e) {
            throw new ProviderUnavailableException("Google's answer was far larger than a token answer can be.");
        }
    }

    private ProviderTokens tokensFrom(GoogleHttp.Answer answer, String what) {
        JsonNode body = http.json(answer);
        String accessToken = GoogleHttp.text(body, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new ProviderUnavailableException("Google's " + what + " answer carried no access token.");
        }
        String scope = GoogleHttp.text(body, "scope");
        Set<String> scopes = scope == null
                ? Set.of()
                : Arrays.stream(scope.trim().split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new ProviderTokens(accessToken, GoogleHttp.text(body, "refresh_token"), scopes);
    }

    /** Google's own short error code, only when it is one: it goes into a log line, and a free-form string could hold anything. */
    private String errorCodeOf(GoogleHttp.Answer answer) {
        try {
            String error = GoogleHttp.text(http.json(answer), "error");
            return error != null && error.matches("[a-z_]{1,64}") ? error : null;
        } catch (ProviderUnavailableException e) {
            return null;
        }
    }

    /** Within what the connection table holds; an address too long to store is simply not shown. */
    private static ProviderAccount account(String id, String email) {
        if (id == null || id.isBlank() || id.length() > 255) {
            throw new ProviderUnavailableException("Google did not say which account this is.");
        }
        boolean showable = email != null && !email.isBlank() && email.length() <= 320;
        return new ProviderAccount(id, showable ? email : null);
    }
}
