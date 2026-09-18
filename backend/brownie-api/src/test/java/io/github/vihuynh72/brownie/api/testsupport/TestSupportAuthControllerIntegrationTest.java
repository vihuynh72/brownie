package io.github.vihuynh72.brownie.api.testsupport;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The e2e session-seeding route is a development convenience that mints a
 * real session, so its three guards are proven here against a real
 * Postgres and the real filter chain: it exists only when its token is
 * configured, answers only loopback clients, and answers only the exact
 * configured token. Built-in template provisioning is expected to fail
 * inside this test (there is no blob store or scanner here) and the route
 * must still succeed, since a missing template never blocks a sign-in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=",
    "brownie.test-support.token=integration-test-token"
})
@Testcontainers
class TestSupportAuthControllerIntegrationTest {

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
    static void properties(DynamicPropertyRegistry registry) {
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
    private MockMvc mockMvc;

    @Test
    void aMissingTokenIsRefused() throws Exception {
        mockMvc.perform(get("/test-support/sessions").param("subject", "e2e-no-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void aWrongTokenIsRefused() throws Exception {
        mockMvc.perform(get("/test-support/sessions")
                        .param("subject", "e2e-wrong-token")
                        .header("X-Test-Support-Token", "integration-test-tokem"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRightTokenFromAnotherMachineIsStillRefused() throws Exception {
        mockMvc.perform(get("/test-support/sessions")
                        .param("subject", "e2e-remote")
                        .header("X-Test-Support-Token", "integration-test-token")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.5");
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRightTokenFromLoopbackMintsASessionThatTheProtectedApiAccepts() throws Exception {
        MvcResult seeded = mockMvc.perform(get("/test-support/sessions")
                        .param("subject", "e2e-loopback")
                        .header("X-Test-Support-Token", "integration-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.workspaceId").isNumber())
                .andReturn();
        Cookie session = seeded.getResponse().getCookie("SESSION");
        assertThat(session).as("the seeding response sets the real session cookie").isNotNull();

        mockMvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("e2e-loopback"));
    }
}
