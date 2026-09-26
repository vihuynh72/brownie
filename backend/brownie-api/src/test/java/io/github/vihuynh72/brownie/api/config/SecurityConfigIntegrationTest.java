package io.github.vihuynh72.brownie.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class SecurityConfigIntegrationTest {

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MockMvc mockMvc;

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

    /** The CSRF check runs before authorization, so even an anonymous mutation is refused for the missing token, not for the missing session. */
    @Test
    void mutationWithoutACsrfTokenIsRejected() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/workspaces/1/documents")))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"no csrf token\"}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    /** Picking Drive files, copying one and forgetting one are changes like any other: refused without the CSRF token. */
    @Test
    void theDriveRoutesAreRefusedWithoutACsrfToken() throws Exception {
        for (String path : List.of("/api/v1/workspaces/1/connections/google/drive/picks", "/api/v1/workspaces/1/connections/google/drive/imports",
                "/api/v1/workspaces/1/connections/google/drive/files/1/forget")) {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url(path)))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).as(path).isEqualTo(403);
        }
    }

    /**
     * A load balancer has no session: health answers anonymously on the
     * management port, and nothing else there does.
     *
     * <p>What is checked is that it answers, not that it says everything is
     * well. Health now includes readings from the blob store and the virus
     * scanner, and this context is deliberately pointed at addresses nothing
     * is listening on, so the honest answer here is 503 with a document
     * saying so -- which is itself the point of those readings. Asserting 200
     * would only pass by making health blind to its dependencies again.
     */
    @Test
    void healthIsAnsweredWithoutASessionAndTheRestOfTheManagementPortIsNot() throws Exception {
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isIn(200, 503);
        assertThat(health.body()).contains("\"status\"");

        HttpResponse<String> discovery = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(discovery.statusCode()).isEqualTo(401);
    }

    /** The limits a person sees before choosing a file are the ones the upload route enforces, and they need a session like everything else. */
    @Test
    void capabilitiesAreReadableWithASessionAndNotWithout() throws Exception {
        mockMvc.perform(get("/api/v1/capabilities").with(user("someone")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUploadBytes").value(10485760))
                .andExpect(jsonPath("$.uploadMediaTypes[*].extension").value(org.hamcrest.Matchers.hasItems("docx", "pdf", "txt")))
                .andExpect(jsonPath("$.assistSourceMediaTypes[0]").value("text/plain"))
                .andExpect(jsonPath("$.templateMediaTypes[0]").value("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andExpect(jsonPath("$.googleConnectorAccess").isEmpty());

        HttpResponse<String> anonymous = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/capabilities"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    /** A route nobody registered as public is closed by default: the same plain 401 as the protected API, never the controller's own answer. */
    @Test
    void anUnlistedRouteRequiresASessionByDefault() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/probe/denied"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
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

    @Test
    void aDeniedCapabilityCheckGetsTheSameStructuredJsonEveryOtherErrorUses() throws Exception {
        // Signed in (so the default authentication requirement is satisfied) and
        // then refused by application code: the shape of that refusal is what
        // this proves, through the real filter chain and the real handler.
        MvcResult result = mockMvc.perform(get("/probe/denied").with(user("someone")))
                .andExpect(status().isForbidden())
                .andReturn();
        Map<String, Object> body = new ObjectMapper().readValue(result.getResponse().getContentAsString(), Map.class);

        assertThat(body).containsEntry("code", "FORBIDDEN");
        assertThat(body.get("correlationId")).isNotNull();
        assertThat(body.get("fields")).isEqualTo(List.of());
        assertThat(body.get("recoveryActions")).isEqualTo(List.of());
    }

    /**
     * The exact shape of a real provider refusal: sign-in was started here
     * (so a pending authorization request with its state exists in the
     * session), and the provider's callback comes back with an error for
     * that state instead of a code. This used to end on a JSON 404 at
     * {@code /login?error}, a page this API never generates; it must land
     * on the web app with the provider's own error code instead.
     */
    @Test
    void aFailedSignInCallbackLandsOnTheWebAppWithTheProvidersErrorCodeInsteadOfA404() throws Exception {
        HttpResponse<Void> started = client.send(
                HttpRequest.newBuilder(URI.create(url("/oauth2/authorization/entra"))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        String authorizeLocation = started.headers().firstValue("Location").orElseThrow();
        String state = queryParameter(authorizeLocation, "state");
        // Every cookie the redirect set, not just the first: the CSRF cookie is set alongside the
        // session cookie, and only the latter carries the pending authorization request's state.
        String sessionCookie = String.join("; ", started.headers().allValues("Set-Cookie").stream()
                .map(cookie -> cookie.split(";", 2)[0])
                .toList());
        assertThat(sessionCookie).contains("JSESSIONID=");

        HttpResponse<String> callback = client.send(
                HttpRequest.newBuilder(URI.create(url("/login/oauth2/code/entra"
                                + "?error=access_denied"
                                + "&error_description=" + URLEncoder.encode("AADSTS500208: not a valid login domain", StandardCharsets.UTF_8)
                                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8))))
                        .header("Cookie", sessionCookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .startsWith("http://")
                        .endsWith("/?signin=failed&reason=access_denied"));
    }

    /** A callback with no sign-in pending for it (a stale tab, a forged request) is still a failure with a real destination, never a 404. */
    @Test
    void aCallbackWithNoPendingSignInStillLandsSomewhereReal() throws Exception {
        HttpResponse<String> callback = client.send(
                HttpRequest.newBuilder(URI.create(url("/login/oauth2/code/entra?error=access_denied&state=nothing-pending")))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location)
                        .endsWith("/?signin=failed&reason=authorization_request_not_found"));
    }

    private static String queryParameter(String url, String name) {
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("No query parameter " + name + " in " + url);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Exists only in this test's own context, purely to give {@code
     * ApiExceptionHandler} a route that throws {@link AccessDeniedException}
     * to handle -- nothing in the shipped product throws it from an
     * unprotected route yet, but Spring MVC's exception handling catches it
     * exactly the same way regardless of where it is thrown.
     */
    @TestConfiguration
    static class DeniedProbeConfiguration {
        @Bean
        DeniedProbeController deniedProbeController() {
            return new DeniedProbeController();
        }
    }

    @RestController
    static class DeniedProbeController {
        @GetMapping("/probe/denied")
        String denied() {
            throw new AccessDeniedException("denied for this test");
        }
    }
}
