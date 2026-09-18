package io.github.vihuynh72.brownie.api.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The excerpt route is a document's window onto its own evidence and
 * nothing else's: the same span resolves through the document whose
 * sources include the snapshot it cites, is refused through a document
 * in the same workspace that never attached that source, and is refused
 * through another workspace altogether -- all as a plain 404, so a
 * guessed span id learns nothing. Proven through real HTTP against real
 * Postgres, Azurite, and ClamAV, the same way the source routes are.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class DocumentEvidenceIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-document-evidence";

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
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("clamav/clamav-debian:1.4"))
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started\\.\\n", 1))
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

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
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    @Test
    void aDocumentShowsOnlyTheExcerptsItsOwnSourcesCite() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-evidence-owner");
        long workspaceId = ensureWorkspace("subject-evidence-owner").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-evidence-owner").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        JsonNode flowing = findByDisplayName(
                readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()),
                "Flowing meeting minutes");
        long templateId = flowing.get("id").asLong();
        long templateVersionId = flowing.get("currentActiveVersionId").asLong();
        long documentId = createMinimalDocument(session, workspaceId, templateId, templateVersionId);
        long otherDocumentId = createMinimalDocument(session, workspaceId, templateId, templateVersionId);
        long artifactId = uploadAndFinalize(session, workspaceId, "Meeting called to order by Priya Rao.");

        JsonNode attached = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/sources")
                        .cookie(session).with(csrf())
                        .contentType("application/json").content("{\"artifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        long snapshotId = attached.get("id").asLong();
        long spanId = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/sources/" + snapshotId + "/spans")
                        .cookie(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"type\":\"PLAIN_TEXT\",\"startCodePoint\":27,\"endCodePointExclusive\":36}"))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();

        JsonNode excerpt = readJson(mockMvc.perform(get(evidencePath(workspaceId, documentId, spanId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(excerpt.get("spanId").asLong()).isEqualTo(spanId);
        assertThat(excerpt.get("sourceSnapshotId").asLong()).isEqualTo(snapshotId);
        assertThat(excerpt.get("sourceArtifactId").asLong()).isEqualTo(artifactId);
        assertThat(excerpt.get("displayFilename").asText()).isEqualTo("transcript.txt");
        assertThat(excerpt.get("locatorType").asText()).isEqualTo("PLAIN_TEXT");
        assertThat(excerpt.get("excerptText").asText()).isEqualTo("Priya Rao");

        // The same workspace, a document that never attached this source: refused.
        mockMvc.perform(get(evidencePath(workspaceId, otherDocumentId, spanId)).cookie(session))
                .andExpect(status().isNotFound());
        // A span nobody created: refused the same way.
        mockMvc.perform(get(evidencePath(workspaceId, documentId, spanId + 1_000_000)).cookie(session))
                .andExpect(status().isNotFound());

        // Another workspace, guessing both ids through its own document: refused.
        Cookie intruderSession = loginAndGetSessionCookie("subject-evidence-intruder");
        long intruderWorkspaceId = ensureWorkspace("subject-evidence-intruder").id();
        long intruderUserId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-evidence-intruder").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(intruderWorkspaceId, intruderUserId);
        JsonNode intruderTemplate = findByDisplayName(
                readJson(mockMvc.perform(get("/api/v1/workspaces/" + intruderWorkspaceId + "/templates").cookie(intruderSession))
                        .andExpect(status().isOk())
                        .andReturn()),
                "Flowing meeting minutes");
        long intruderDocumentId = createMinimalDocument(
                intruderSession, intruderWorkspaceId, intruderTemplate.get("id").asLong(), intruderTemplate.get("currentActiveVersionId").asLong());
        mockMvc.perform(get(evidencePath(intruderWorkspaceId, intruderDocumentId, spanId)).cookie(intruderSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(evidencePath(intruderWorkspaceId, documentId, spanId)).cookie(intruderSession))
                .andExpect(status().isNotFound());
    }

    private static String evidencePath(long workspaceId, long documentId, long spanId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/evidence/" + spanId;
    }

    private static JsonNode findByDisplayName(JsonNode templatesResponse, String displayName) {
        for (JsonNode template : templatesResponse) {
            if (template.get("displayName").asText().equals(displayName)) {
                return template;
            }
        }
        throw new AssertionError("No template named \"" + displayName + "\" in " + templatesResponse);
    }

    private long createMinimalDocument(Cookie session, long workspaceId, long templateId, long templateVersionId) throws Exception {
        String body = "{"
                + "\"title\":\"Weekly Sync\","
                + "\"templateId\":" + templateId + ","
                + "\"templateVersionId\":" + templateVersionId + ","
                + "\"fields\":{},"
                + "\"initialRevisionReason\":\"Created for an evidence test.\"}";
        JsonNode created = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
        return created.get("id").asLong();
    }

    private long uploadAndFinalize(Cookie session, long workspaceId, String text) throws Exception {
        JsonNode allocateResponse = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/uploads")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"filename\":\"transcript.txt\"}"))
                .andExpect(status().isCreated())
                .andReturn());
        long artifactId = allocateResponse.get("id").asLong();
        mockMvc.perform(put("/api/v1/workspaces/" + workspaceId + "/uploads/" + artifactId + "/content")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/octet-stream")
                        .content(text.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        JsonNode completed = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/uploads/" + artifactId + "/complete")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(completed.get("status").asText()).isEqualTo("READY");
        return artifactId;
    }

    private JsonNode readJson(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private Cookie loginAndGetSessionCookie(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        Workspace workspace = ensureWorkspace(subject);
        assertThat(workspace).isNotNull();
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        Session session = createAuthenticatedSession(sessionRepository, context);
        return new Cookie(
                "SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String subject) {
        var identity = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
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
