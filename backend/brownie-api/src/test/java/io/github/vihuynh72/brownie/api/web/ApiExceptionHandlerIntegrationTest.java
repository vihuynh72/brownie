package io.github.vihuynh72.brownie.api.web;

import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the actual, deployed error-response contract end to end: a real
 * HTTP call, through the real filter chain and the real {@link
 * ApiExceptionHandler}, for every case this module can currently produce.
 * {@link ProbeController} exists only in this test's own Spring context --
 * it is not shipped -- purely to give an unmapped route, an unexpected
 * exception, and a failed validation something real to happen against;
 * nothing about the exception-handling contract itself is test-only.
 * Runs against the disposable in-memory H2 database from the {@code test}
 * profile block in {@code application.yml} -- needed for {@code
 * PlatformProbeController}'s repository to wire up, though this test
 * never touches its actual schema -- with Flyway excluded, since its one
 * migration is Postgres-specific SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class ApiExceptionHandlerIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void unmappedRouteReturnsEnrichedNotFound() throws Exception {
        HttpResponse<String> response = get("/does-not-exist", null);
        Map<String, Object> body = json.readValue(response.body(), Map.class);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body).containsEntry("code", "NOT_FOUND");
        assertThat(body.get("correlationId")).isNotNull();
        assertThat(response.headers().firstValue(CorrelationIdFilter.HEADER_NAME)).isPresent();
    }

    @Test
    void callerSuppliedCorrelationIdIsEchoedIntoTheErrorBody() throws Exception {
        HttpResponse<String> response = get("/does-not-exist", "test-supplied-id-42");
        Map<String, Object> body = json.readValue(response.body(), Map.class);

        assertThat(response.headers().firstValue(CorrelationIdFilter.HEADER_NAME))
                .contains("test-supplied-id-42");
        assertThat(body).containsEntry("correlationId", "test-supplied-id-42");
    }

    @Test
    void unexpectedExceptionReturnsSafeGenericMessageNotTheRealOne() throws Exception {
        HttpResponse<String> response = get("/probe/boom", null);
        Map<String, Object> body = json.readValue(response.body(), Map.class);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(body).containsEntry("code", "INTERNAL_ERROR");
        assertThat(body.get("correlationId")).isNotNull();
        assertThat(String.valueOf(body.get("detail"))).doesNotContain("the real secret failure reason");
    }

    @Test
    void failedValidationReportsTheAffectedField() throws Exception {
        String csrfToken = fetchCsrfToken();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/probe/validated")))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = json.readValue(response.body(), Map.class);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body).containsEntry("code", "VALIDATION_FAILED");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> fields = (List<Map<String, String>>) body.get("fields");
        assertThat(fields).extracting(field -> field.get("field")).contains("name");
    }

    private HttpResponse<String> get(String path, String correlationId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (correlationId != null) {
            builder.header(CorrelationIdFilter.HEADER_NAME, correlationId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Spring Security's {@code csrf.spa()} sets the XSRF-TOKEN cookie on
     * every response, even this unrelated GET, precisely so a JSON client
     * never needs a dedicated endpoint just to obtain one before its first
     * mutation.
     */
    private String fetchCsrfToken() throws IOException, InterruptedException {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/does-not-exist"))).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        String setCookie = response.headers().firstValue("set-cookie").orElseThrow();
        return setCookie.split(";", 2)[0].split("=", 2)[1];
    }

    @TestConfiguration
    static class ProbeControllerConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("the real secret failure reason");
        }

        @PostMapping("/probe/validated")
        String validated(@Valid @RequestBody ProbeRequest request) {
            return "ok";
        }
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
