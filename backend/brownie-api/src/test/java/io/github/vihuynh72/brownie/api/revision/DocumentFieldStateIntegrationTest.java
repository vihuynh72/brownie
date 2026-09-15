package io.github.vihuynh72.brownie.api.revision;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the two new field-dimension routes for real, and a real,
 * previously-unreachable gap they expose for the first time: {@code
 * FieldLockedException} has existed in this codebase for a while but was
 * never registered in {@code ApiExceptionHandler}, since no route could ever
 * make a field {@code EXPLICITLY_LOCKED} until this task's own lock
 * route -- before this task, a direct edit against a locked field would
 * have 500ed rather than reporting a real, meaningful conflict.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class DocumentFieldStateIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-document-field-state";

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
    void recordsAReviewDecisionLocksAFieldAndRejectsADirectEditAgainstItWhileLeavingOtherFieldsEditable() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-doc-field-state");
        long workspaceId = ensureWorkspace("subject-doc-field-state").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-doc-field-state").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        long[] templateAndVersion = findFlowingTemplateAndActiveVersion(session, workspaceId);

        long documentId = createMinimalDocument(session, workspaceId, templateAndVersion[0], templateAndVersion[1]);
        long revisionId = currentRevisionId(session, workspaceId, documentId);

        JsonNode afterReview = readJson(mockMvc.perform(post(fieldsPath(workspaceId, documentId) + "/review-decision")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"fieldId\":\"meeting.title\",\"decision\":\"ACCEPTED\"}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(afterReview.get("fields").get("meeting.title").get("fieldState").get("review").asText()).isEqualTo("ACCEPTED");
        long revisionAfterReview = afterReview.get("id").asLong();

        JsonNode afterLock = readJson(mockMvc.perform(post(fieldsPath(workspaceId, documentId) + "/lock")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionAfterReview + ",\"fieldId\":\"meeting.date\",\"lock\":\"EXPLICITLY_LOCKED\"}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(afterLock.get("fields").get("meeting.date").get("fieldState").get("lock").asText()).isEqualTo("EXPLICITLY_LOCKED");
        // The review decision from the previous revision carried forward untouched onto this new one.
        assertThat(afterLock.get("fields").get("meeting.title").get("fieldState").get("review").asText()).isEqualTo("ACCEPTED");
        long revisionAfterLock = afterLock.get("id").asLong();

        mockMvc.perform(patch("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/content")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionAfterLock + ",\"editReason\":\"Trying to edit a locked field.\","
                                + "\"edits\":[{\"operation\":\"SET\",\"fieldId\":\"meeting.date\","
                                + "\"value\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2026-04-01\"}}]}"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("FIELD_LOCKED"));

        JsonNode afterUnlockedEdit = readJson(mockMvc.perform(
                        patch("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/content")
                                .cookie(session)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"expectedRevisionId\":" + revisionAfterLock + ",\"editReason\":\"Editing an unlocked field.\","
                                        + "\"edits\":[{\"operation\":\"SET\",\"fieldId\":\"meeting.title\","
                                        + "\"value\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Updated Title\"}}]}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(afterUnlockedEdit.get("fields").get("meeting.title").get("value").asText()).isEqualTo("Updated Title");
        assertThat(afterUnlockedEdit.get("fields").get("meeting.date").get("fieldState").get("lock").asText()).isEqualTo("EXPLICITLY_LOCKED");
    }

    private long currentRevisionId(Cookie session, long workspaceId, long documentId) throws Exception {
        JsonNode document = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        return document.get("currentRevision").get("id").asLong();
    }

    private long[] findFlowingTemplateAndActiveVersion(Cookie session, long workspaceId) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        for (JsonNode template : templates) {
            if (template.get("displayName").asText().equals("Flowing meeting minutes")) {
                return new long[] {template.get("id").asLong(), template.get("currentActiveVersionId").asLong()};
            }
        }
        throw new AssertionError("Flowing meeting minutes template was not provisioned: " + templates);
    }

    private long createMinimalDocument(Cookie session, long workspaceId, long templateId, long templateVersionId) throws Exception {
        String body = "{"
                + "\"title\":\"Weekly Sync\","
                + "\"templateId\":" + templateId + ","
                + "\"templateVersionId\":" + templateVersionId + ","
                + "\"fields\":{\"meeting.title\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Weekly Sync\"},"
                + "\"meeting.date\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2026-03-12\"}},"
                + "\"initialRevisionReason\":\"Created for a field-state test.\"}";
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

    private static String fieldsPath(long workspaceId, long documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/fields";
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
