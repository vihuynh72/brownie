package io.github.vihuynh72.brownie.api.template;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves {@link BuiltInTemplateProvisioningService} end to end against real
 * infrastructure: real Postgres, Azurite, ClamAV, and the real
 * Docker-isolated LibreOffice renderer that template activation depends on
 * -- the same infrastructure pattern {@code TemplateIntegrationTest}
 * already established. Calls the provisioning service directly rather than
 * driving a real OIDC login (impossible here without a real identity
 * provider); {@code BrownieOidcUserServiceTest} separately proves that the
 * real login path calls this service with the just-created workspace.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class BuiltInTemplateProvisioningIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-builtin-template-provisioning";

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
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
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

    @Autowired
    private io.github.vihuynh72.brownie.core.artifact.ArtifactRepository artifactRepository;

    @Autowired
    private io.github.vihuynh72.brownie.core.artifact.BlobStore blobStore;

    @Autowired
    private io.github.vihuynh72.brownie.core.artifact.ArtifactService artifactService;

    @Autowired
    private io.github.vihuynh72.brownie.core.document.DocumentExtractionService documentExtractionService;

    @Autowired
    private io.github.vihuynh72.brownie.core.template.TemplateService templateService;

    @Test
    void bothBuiltInTemplatesAreActivatedAndListableAfterProvisioning() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-fresh-workspace");
        long workspaceId = ensureWorkspace("subject-fresh-workspace").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-fresh-workspace").orElseThrow().id();

        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        JsonNode templates = readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(templates).hasSize(2);
        List<String> displayNames = List.of(templates.get(0).get("displayName").asText(), templates.get(1).get("displayName").asText());
        assertThat(displayNames).containsExactlyInAnyOrder("Flowing meeting minutes", "Table-led meeting minutes");
        for (JsonNode template : templates) {
            assertThat(template.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(template.get("currentActiveVersionId").isNull()).isFalse();
        }

        long flowingTemplateId = templates.get(0).get("displayName").asText().equals("Flowing meeting minutes")
                ? templates.get(0).get("id").asLong()
                : templates.get(1).get("id").asLong();
        long flowingVersionId = templates.get(0).get("displayName").asText().equals("Flowing meeting minutes")
                ? templates.get(0).get("currentActiveVersionId").asLong()
                : templates.get(1).get("currentActiveVersionId").asLong();
        JsonNode version = readJson(mockMvc.perform(get(templatesPath(workspaceId) + "/" + flowingTemplateId + "/versions/" + flowingVersionId)
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(version.get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(version.get("fields")).isNotEmpty();
        List<String> fieldIds = new java.util.ArrayList<>();
        version.get("fields").forEach(field -> fieldIds.add(field.get("fieldId").asText()));
        assertThat(fieldIds).contains("meeting.title", "meeting.date");
    }

    /**
     * The rows survive a blob-store reset; the bytes do not. Before the
     * repair, downloading the built-in's DOCX is a named storage failure
     * and the workspace is stuck with it; running provisioning again
     * writes the packaged bytes back under the same key, and the download
     * works.
     */
    @Test
    void aBuiltInWhoseStoredBytesWentMissingIsRepairedOnTheNextProvisioningRun() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-lost-bytes");
        long workspaceId = ensureWorkspace("subject-lost-bytes").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-lost-bytes").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        JsonNode templates = readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode flowing = templates.get(0).get("displayName").asText().equals("Flowing meeting minutes") ? templates.get(0) : templates.get(1);
        JsonNode version = readJson(mockMvc.perform(get(templatesPath(workspaceId) + "/" + flowing.get("id").asLong() + "/versions/"
                        + flowing.get("currentActiveVersionId").asLong()).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        long artifactId = version.get("sourceArtifactId").asLong();
        String blobKey = artifactRepository.find(workspaceId, userId, artifactId).orElseThrow().blobKey();
        String downloadPath = "/api/v1/workspaces/" + workspaceId + "/uploads/" + artifactId + "/download";
        mockMvc.perform(get(downloadPath).cookie(session)).andExpect(status().isOk());

        blobStore.delete(blobKey);
        assertThat(blobStore.sizeOf(blobKey)).isEmpty();
        mockMvc.perform(get(downloadPath).cookie(session))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ARTIFACT_STORAGE_FAILURE"));

        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        assertThat(blobStore.sizeOf(blobKey)).isPresent();
        mockMvc.perform(get(downloadPath).cookie(session)).andExpect(status().isOk());
        assertThat(readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session)).andExpect(status().isOk()).andReturn()))
                .hasSize(2);
    }

    @Test
    void runningProvisioningTwiceDoesNotCreateDuplicateTemplates() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-idempotent-provisioning");
        long workspaceId = ensureWorkspace("subject-idempotent-provisioning").id();
        long userId =
                userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-idempotent-provisioning").orElseThrow().id();

        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        JsonNode templates = readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(templates).hasSize(2);
    }

    @Test
    void aWorkspaceThatAlreadyHasACustomTemplateIsNeverGivenBuiltInsToo() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-custom-first");
        long workspaceId = ensureWorkspace("subject-custom-first").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-custom-first").orElseThrow().id();

        // A template already exists (created directly, bypassing the full teaching flow --
        // this test only needs a row to exist, not a usable one) before provisioning ever runs.
        long artifactId = uploadMinimalDocx(session, workspaceId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(templatesPath(workspaceId))
                        .cookie(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Owner's Own Template\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated());

        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        JsonNode templates = readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).get("displayName").asText()).isEqualTo("Owner's Own Template");
    }

    /**
     * What a first sign-in leaves behind when the renderer is down or busy at its last step: a built-in that was
     * drafted and never activated, and a second one that was never reached.
     */
    @Test
    void aBuiltInLeftNeverActivatedByAnInterruptedAttemptIsFinishedAndTheMissingOneIsCreated() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-interrupted-provisioning");
        long workspaceId = ensureWorkspace("subject-interrupted-provisioning").id();
        long userId =
                userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-interrupted-provisioning").orElseThrow().id();
        var first = io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry.all().get(0);
        draftFrom(workspaceId, userId, first.displayName(), packagedBytes(first.id()));

        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        JsonNode templates = readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(templates).hasSize(2);
        for (JsonNode template : templates) {
            assertThat(template.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(template.get("currentActiveVersionId").isNull()).isFalse();
        }
    }

    @Test
    void anOwnersOwnDraftUnderABuiltInsNameIsNotTakenForAnInterruptedAttempt() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-own-draft-same-name");
        long workspaceId = ensureWorkspace("subject-own-draft-same-name").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-own-draft-same-name").orElseThrow().id();
        var builtIns = io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry.all();
        // The first built-in's name over a different file: the name matches, what it was made from does not.
        draftFrom(workspaceId, userId, builtIns.get(0).displayName(), packagedBytes(builtIns.get(1).id()));

        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        JsonNode templates = readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(templates).hasSize(1);
        assertThat(templates.get(0).get("status").asText()).isEqualTo("DRAFT");
    }

    /** The same new person signing in twice at the same moment, from two tabs or two devices. */
    @Test
    void twoSignInsAtTheSameMomentPrepareTheBuiltInsOnce() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-two-sign-ins-at-once");
        long workspaceId = ensureWorkspace("subject-two-sign-ins-at-once").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-two-sign-ins-at-once").orElseThrow().id();
        java.util.concurrent.CountDownLatch together = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<?>> both = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    together.await();
                    builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
                    return null;
                }));
            }
            together.countDown();
            for (java.util.concurrent.Future<?> one : both) {
                one.get(120, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        JsonNode templates = readJson(mockMvc.perform(get(templatesPath(workspaceId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(templates).hasSize(2);
    }

    private void draftFrom(long workspaceId, long userId, String displayName, byte[] docx) {
        var artifact = artifactService.initiateUpload(workspaceId, userId, displayName + ".docx");
        artifactService.receiveContent(workspaceId, userId, artifact.id(), new java.io.ByteArrayInputStream(docx));
        artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        documentExtractionService.extract(workspaceId, userId, artifact.id());
        templateService.createDraft(workspaceId, userId, displayName, artifact.id());
    }

    private static byte[] packagedBytes(String builtInId) throws java.io.IOException {
        try (var in = new org.springframework.core.io.ClassPathResource("builtin-templates/" + builtInId + ".docx").getInputStream()) {
            return in.readAllBytes();
        }
    }

    private long uploadMinimalDocx(Cookie session, long workspaceId) throws Exception {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            doc.createParagraph().createRun().setText("Not extracted in this test.");
            var out = new java.io.ByteArrayOutputStream();
            doc.write(out);
            JsonNode allocateResponse = readJson(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/workspaces/" + workspaceId + "/uploads")
                            .cookie(session)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType("application/json")
                            .content("{\"filename\":\"custom.docx\"}"))
                    .andExpect(status().isCreated())
                    .andReturn());
            long artifactId = allocateResponse.get("id").asLong();
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/workspaces/" + workspaceId + "/uploads/" + artifactId + "/content")
                            .cookie(session)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .contentType("application/octet-stream")
                            .content(out.toByteArray()))
                    .andExpect(status().isOk());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/workspaces/" + workspaceId + "/uploads/" + artifactId + "/complete")
                            .cookie(session)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isOk());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/workspaces/" + workspaceId + "/artifacts/" + artifactId + "/extraction")
                            .cookie(session)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().isOk());
            return artifactId;
        }
    }

    private static String templatesPath(long workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/templates";
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
