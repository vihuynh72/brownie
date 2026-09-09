package io.github.vihuynh72.brownie.api.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
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
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole allocate/upload/complete flow end to end against real
 * infrastructure: a real Postgres (row-level security included) and a
 * real Azurite, driven entirely through MockMvc-issued HTTP requests
 * carrying a real authenticated session and a real CSRF token, the same
 * way {@code SessionRevocationIntegrationTest} builds one -- not a
 * fabricated principal handed straight to a controller method.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.autoconfigure.exclude=", "brownie.artifacts.max-upload-bytes=20"})
@Testcontainers
class ArtifactUploadIntegrationTest {

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
    static final AzuriteContainer AZURITE =
            new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    // A plain, local instance rather than an autowired bean: this Boot
    // line's web starter does not register an ObjectMapper bean of its
    // own (Spring MVC falls back to building one internally for message
    // conversion instead), so there is nothing to inject -- confirmed by
    // an UnsatisfiedDependencyException on first attempting @Autowired
    // here, not assumed. This test only needs to read a few plain fields
    // out of a response body, not the application's exact configured
    // serialization behavior.
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
    void theWholeAllocateUploadCompleteFlowWorksAgainstRealPostgresAndRealAzurite() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-owner");
        long workspaceId = workspaceIdFor("subject-owner");

        long artifactId = allocate(owner, workspaceId);

        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8); // 11 bytes, under the 20-byte test limit
        JsonNode uploadResponse = uploadContent(owner, workspaceId, artifactId, content);
        assertThat(uploadResponse.get("status").asText()).isEqualTo("UPLOADING");
        assertThat(uploadResponse.get("byteCount").asLong()).isEqualTo(content.length);
        String expectedSha256 = sha256Hex(content);
        assertThat(uploadResponse.get("sha256").asText()).isEqualTo(expectedSha256);

        JsonNode completeResponse = complete(owner, workspaceId, artifactId);
        assertThat(completeResponse.get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(completeResponse.get("byteCount").asLong()).isEqualTo(content.length);
        assertThat(completeResponse.get("sha256").asText()).isEqualTo(expectedSha256);

        // Idempotent: completing an already-QUARANTINED artifact again
        // succeeds and returns the same, unchanged result.
        JsonNode secondComplete = complete(owner, workspaceId, artifactId);
        assertThat(secondComplete.get("status").asText()).isEqualTo("QUARANTINED");
        assertThat(secondComplete.get("byteCount").asLong()).isEqualTo(content.length);
    }

    @Test
    void aNonMemberCannotAllocateAnUploadInSomeoneElsesWorkspace() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-victim");
        long workspaceId = workspaceIdFor("subject-victim");
        Cookie outsider = loginAndGetSessionCookie("subject-outsider");

        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/uploads", workspaceId)
                        .cookie(outsider)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        // The legitimate owner can still allocate normally in their own workspace.
        mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/uploads", workspaceId)
                        .cookie(owner)
                        .with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    void contentOverTheConfiguredLimitIsRejected() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-large-upload");
        long workspaceId = workspaceIdFor("subject-large-upload");
        long artifactId = allocate(owner, workspaceId);

        byte[] tooLarge = "this string is definitely over twenty bytes".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf())
                        .content(tooLarge))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void completingBeforeAnyContentIsUploadedIsAConflict() throws Exception {
        Cookie owner = loginAndGetSessionCookie("subject-no-content");
        long workspaceId = workspaceIdFor("subject-no-content");
        long artifactId = allocate(owner, workspaceId);

        mockMvc.perform(post(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/complete",
                                workspaceId,
                                artifactId)
                        .cookie(owner)
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    private long allocate(Cookie sessionCookie, long workspaceId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces/{workspaceId}/uploads", workspaceId)
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body).get("id").asLong();
    }

    private JsonNode uploadContent(Cookie sessionCookie, long workspaceId, long artifactId, byte[] content)
            throws Exception {
        String body = mockMvc.perform(put(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/content",
                                workspaceId,
                                artifactId)
                        .cookie(sessionCookie)
                        .with(csrf())
                        .content(content))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }

    private JsonNode complete(Cookie sessionCookie, long workspaceId, long artifactId) throws Exception {
        String body = mockMvc.perform(post(
                                "/api/v1/workspaces/{workspaceId}/uploads/{artifactId}/complete",
                                workspaceId,
                                artifactId)
                        .cookie(sessionCookie)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }

    private long workspaceIdFor(String subject) {
        UserIdentity identity = userIdentityRepository
                .findByIssuerAndSubject("https://issuer-artifact-upload", subject)
                .orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
    }

    /**
     * Builds a real, authenticated session through the exact repository
     * bean the application uses at runtime, then returns the cookie a
     * browser would present for it -- Spring Security Test's own {@code
     * oidcLogin()} helper authenticates onto a throwaway servlet session
     * created before Spring Session's filter ever runs, which never
     * touches spring_session at all (see SessionRevocationIntegrationTest).
     */
    private Cookie loginAndGetSessionCookie(String subject) {
        String issuer = "https://issuer-artifact-upload";
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
                "SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String issuer, String subject) {
        UserIdentity identity = userIdentityRepository.findByIssuerAndSubject(issuer, subject).orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id());
    }

    /** A generic type parameter ties createSession() and save() to the same concrete session type, avoiding a wildcard-capture mismatch across two separate calls. */
    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private static String sha256Hex(byte[] content) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(content));
    }
}
