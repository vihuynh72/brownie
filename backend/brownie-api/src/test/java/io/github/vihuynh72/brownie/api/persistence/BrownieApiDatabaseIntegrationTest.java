package io.github.vihuynh72.brownie.api.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the actual, configured application wiring against a real,
 * disposable Postgres: Flyway runs at startup using the migration role
 * (see {@code spring.flyway.*} in application.yml), and the application's
 * own {@link DataSource} bean -- the one every future repository will use
 * -- authenticates as the restricted {@code brownie_api} role created by
 * {@code infra/local/postgres/init/01-app-roles.sql}.
 *
 * <p>The negative case (schema changes are refused) matters as much as the
 * positive one (ordinary reads/writes work): it is the actual security
 * property behind "separate runtime credentials" in the master plan, not
 * just an organizational convention.
 *
 * <p>Also proves the one persisted command/query demonstrated over real
 * HTTP: {@code io.github.vihuynh72.brownie.api.platform.PlatformProbeController}'s
 * create-then-read round trip, exercised as a real client would.
 */

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class BrownieApiDatabaseIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private DataSource dataSource;

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void flywayRanAsMigrationRoleAndApiRoleCanReadWhatItCreated() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // flyway_schema_history is created by brownie_migration during context
            // startup, after 01-app-roles.sql already set the default-privilege
            // rule -- so brownie_api reading it here is the real, end-to-end proof
            // that the privilege cascade actually applies to Flyway's own output,
            // not a fabricated table built just for this test.
            var results = statement.executeQuery("SELECT installed_rank FROM flyway_schema_history LIMIT 1");
            assertThat(results).isNotNull();
        }
    }

    @Test
    void apiRoleCannotCreateOrAlterTables() {
        assertThatThrownBy(() -> {
                    try (Connection connection = dataSource.getConnection();
                            Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE should_never_exist (id int)");
                    }
                })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void createThenReadAPlatformProbeOverRealHttp() throws Exception {
        String csrfToken = fetchCsrfToken();
        HttpRequest createRequest = HttpRequest.newBuilder(URI.create(url("/api/v1/platform/probes")))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"end-to-end check\"}"))
                .build();
        HttpResponse<String> createResponse = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(createResponse.statusCode()).isEqualTo(201);
        Map<String, Object> created = json.readValue(createResponse.body(), Map.class);
        assertThat(created.get("message")).isEqualTo("end-to-end check");
        assertThat(createResponse.headers().firstValue("Location")).isPresent();

        long id = ((Number) created.get("id")).longValue();
        HttpResponse<String> getResponse = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/platform/probes/" + id))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> fetched = json.readValue(getResponse.body(), Map.class);

        assertThat(getResponse.statusCode()).isEqualTo(200);
        assertThat(fetched.get("id")).isEqualTo(created.get("id"));
        assertThat(fetched.get("message")).isEqualTo("end-to-end check");
    }

    @Test
    void readingAMissingProbeReturnsTheStandardErrorShape() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/platform/probes/999999999"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = json.readValue(response.body(), Map.class);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(body).containsEntry("code", "NOT_FOUND");
        assertThat(body.get("correlationId")).isNotNull();
    }

    @Test
    void creatingAProbeWithAMissingMessageFailsValidation() throws Exception {
        String csrfToken = fetchCsrfToken();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/v1/platform/probes")))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrfToken)
                .header("X-XSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = json.readValue(response.body(), Map.class);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body).containsEntry("code", "VALIDATION_FAILED");
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
    private String fetchCsrfToken() throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/platform/probes/999999999")))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        String setCookie = response.headers().firstValue("set-cookie").orElseThrow();
        return setCookie.split(";", 2)[0].split("=", 2)[1];
    }
}
