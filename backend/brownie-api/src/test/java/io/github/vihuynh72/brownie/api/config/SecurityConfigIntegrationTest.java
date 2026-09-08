package io.github.vihuynh72.brownie.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the browser/session boundary contract end to end, over real HTTP
 * against the real filter chain: a protected API route answers
 * unauthenticated requests with a plain 401
 * rather than redirecting to the identity provider (this is a JSON API,
 * not a page a browser can be silently bounced through); login initiation
 * actually redirects to the configured authorization endpoint; and every
 * mutation -- including logout itself -- is refused without a valid CSRF
 * token, regardless of whether the route requires authentication.
 *
 * <p>Flyway is excluded for the same reason as {@code
 * ApiExceptionHandlerIntegrationTest}: this profile's H2 database is
 * disposable and in-memory, and this test needs no domain schema at all,
 * only the security filter chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class SecurityConfigIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void unauthenticatedRequestToMeReturnsPlain401NotARedirectToLogin() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/me"))).GET().build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void loginInitiationRedirectsToTheConfiguredAuthorizationEndpoint() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/authorization/entra")))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .startsWith("http://localhost:65535/oauth2/authorize"));
    }

    @Test
    void mutationWithoutACsrfTokenIsRejected() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/platform/probes")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"no csrf token\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void logoutWithoutACsrfTokenIsRejected() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/logout")))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
