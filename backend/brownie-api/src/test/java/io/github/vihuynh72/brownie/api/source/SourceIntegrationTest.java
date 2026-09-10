package io.github.vihuynh72.brownie.api.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole attach-snapshot / create-span / resolve-span flow end
 * to end through real HTTP, a real authenticated session, and real
 * Postgres/Azurite/ClamAV -- the same infrastructure pattern {@code
 * ArtifactUploadIntegrationTest}/{@code ExtractionIntegrationTest} already
 * established. Plain text only: the per-format resolution logic itself is
 * already covered exhaustively at the service and repository layers; this
 * test's job is proving the real wiring (auth, controller, service, real
 * database) works, which one format is enough to show.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class SourceIntegrationTest {

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

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(DockerImageName.parse("clamav/clamav-debian:1.4"))
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started\\.\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
        registry.add("brownie.security.clamav.host", CLAMAV::getHost);
        registry.add("brownie.security.clamav.port", () -> CLAMAV.getMappedPort(3310));
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    // A plain, local instance, not @Autowired -- see ArtifactUploadIntegrationTest's
    // own identical comment: this Boot line autoconfigures a Jackson 3
    // ObjectMapper bean, not this com.fasterxml.jackson one.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Test
    void attachingCreatingAndResolvingASpanWorksEndToEndThroughRealHttp() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-source-flow");
        long workspaceId = ensureWorkspace("https://issuer-source-integration", "subject-source-flow").id();
        String text = "Meeting called to order.";
        long artifactId = uploadAndFinalize(session, workspaceId, text.getBytes(StandardCharsets.UTF_8), "notes.txt");

        JsonNode attachResponse = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/sources")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"artifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        long snapshotId = attachResponse.get("id").asLong();
        assertThat(attachResponse.get("artifactId").asLong()).isEqualTo(artifactId);
        assertThat(attachResponse.get("kind").asText()).isEqualTo("ARTIFACT");

        JsonNode createSpanResponse = readJson(mockMvc.perform(
                        post("/api/v1/workspaces/" + workspaceId + "/sources/" + snapshotId + "/spans")
                                .cookie(session)
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"type\":\"PLAIN_TEXT\",\"startCodePoint\":0,\"endCodePointExclusive\":7}"))
                .andExpect(status().isCreated())
                .andReturn());
        long spanId = createSpanResponse.get("id").asLong();
        assertThat(createSpanResponse.get("excerptText").asText()).isEqualTo("Meeting");
        assertThat(createSpanResponse.get("excerptHash").asText()).matches("[0-9a-f]{64}");

        JsonNode resolveResponse = readJson(mockMvc.perform(get(
                                "/api/v1/workspaces/" + workspaceId + "/sources/" + snapshotId + "/spans/" + spanId)
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(resolveResponse.get("excerptText").asText()).isEqualTo("Meeting");
        assertThat(resolveResponse.get("id").asLong()).isEqualTo(spanId);
    }

    @Test
    void creatingASpanWithAnOutOfRangeLocatorReturnsBadRequestAndPersistsNothing() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-source-invalid");
        long workspaceId = ensureWorkspace("https://issuer-source-integration", "subject-source-invalid").id();
        long artifactId = uploadAndFinalize(session, workspaceId, "short".getBytes(StandardCharsets.UTF_8), "notes.txt");

        JsonNode attachResponse = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/sources")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"artifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        long snapshotId = attachResponse.get("id").asLong();

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/sources/" + snapshotId + "/spans")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"type\":\"PLAIN_TEXT\",\"startCodePoint\":0,\"endCodePointExclusive\":999}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void attachingAnotherWorkspacesArtifactReturnsNotFoundRatherThanLeakingItsExistence() throws Exception {
        Cookie sessionA = loginAndGetSessionCookie("subject-source-cross-a");
        Cookie sessionB = loginAndGetSessionCookie("subject-source-cross-b");
        long workspaceA = ensureWorkspace("https://issuer-source-integration", "subject-source-cross-a").id();
        long workspaceB = ensureWorkspace("https://issuer-source-integration", "subject-source-cross-b").id();
        long artifactInA = uploadAndFinalize(sessionA, workspaceA, "text".getBytes(StandardCharsets.UTF_8), "notes.txt");

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceB + "/sources")
                        .cookie(sessionB)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"artifactId\":" + artifactInA + "}"))
                .andExpect(status().isNotFound());
    }

    private long uploadAndFinalize(Cookie session, long workspaceId, byte[] bytes, String filename) throws Exception {
        JsonNode allocateResponse = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/uploads")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"filename\":\"" + filename + "\"}"))
                .andExpect(status().isCreated())
                .andReturn());
        long artifactId = allocateResponse.get("id").asLong();

        mockMvc.perform(put("/api/v1/workspaces/" + workspaceId + "/uploads/" + artifactId + "/content")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/octet-stream")
                        .content(bytes))
                .andExpect(status().isOk());

        JsonNode completeResponse = readJson(mockMvc.perform(post(
                                "/api/v1/workspaces/" + workspaceId + "/uploads/" + artifactId + "/complete")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(completeResponse.get("status").asText()).isEqualTo("READY");
        return artifactId;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private Cookie loginAndGetSessionCookie(String subject) {
        String issuer = "https://issuer-source-integration";
        userIdentityRepository.recordLogin(issuer, subject, null, null);
        Workspace workspace = ensureWorkspace(issuer, subject);
        assertThat(workspace).isNotNull();

        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, issuer)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));

        Session session = createAuthenticatedSession(sessionRepository, context);
        return new Cookie(
                "SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String issuer, String subject) {
        var identity = userIdentityRepository.findByIssuerAndSubject(issuer, subject).orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id());
    }

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }
}
