package io.github.vihuynh72.brownie.api.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.generation.GenerationJobTypes;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.question.QuestionService;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
 * Proves the "gathering" half of the async generation path for real: a
 * real document against a real activated template, a real attached
 * source, a real HTTP call to start extraction -- and then, independent
 * of any worker ever running, that the resulting job is queued with the
 * right dedup hash and that the exact bundle a worker will later read is
 * genuinely sitting in blob storage under that hash's own key, with the
 * real field definitions and cited excerpts inside it. The worker's own
 * side of this contract is proved separately, in {@code
 * GenerationExtractionJobProcessorIntegrationTest} (brownie-worker), since
 * a worker process is a different module this one must never depend on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class GenerationStartIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-generation-start";

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

    @Autowired
    private BlobStore blobStore;

    @Autowired
    private QuestionService questionService;

    @Test
    void startingExtractionQueuesARealJobAndStagesARealReadableBundle() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-generation-start");
        long workspaceId = ensureWorkspace("subject-generation-start").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-start").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode flowing = findByDisplayName(templates, "Flowing meeting minutes");
        long templateId = flowing.get("id").asLong();
        long templateVersionId = flowing.get("currentActiveVersionId").asLong();

        long documentId = createMinimalDocument(session, workspaceId, templateId, templateVersionId);

        String transcript = """
                Weekly Robotics Club Sync

                The meeting was called to order by Priya Rao. Alex Chen agreed to
                finish wiring the practice robot by 2026-03-12.
                """;
        long sourceArtifactId = uploadAndFinalize(session, workspaceId, transcript);

        JsonNode started = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isAccepted())
                .andReturn());
        long jobId = started.get("jobId").asLong();
        assertThat(jobId).isPositive();

        JsonNode job = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/jobs/" + jobId).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(job.get("type").asText()).isEqualTo("generation.extract-facts");
        assertThat(job.get("state").asText()).isEqualTo("QUEUED");
        assertThat(job.get("resourceType").asText()).isEqualTo("document");
        assertThat(job.get("resourceId").asLong()).isEqualTo(documentId);

        // The bundle a worker will read back is content-addressed by the job's
        // own dedup hash -- read directly from the same BlobStore bean the
        // orchestration service used, with no HTTP route of its own, since a
        // customer never reads this object directly.
        String bundleHash = jdbcJobColumn(workspaceId, userId, jobId, "processing_configuration_hash");
        String objectKey = GenerationJobTypes.inputBundleObjectKey(workspaceId, bundleHash);
        JsonNode bundle;
        try (InputStream content = blobStore.openStream(objectKey)) {
            bundle = OBJECT_MAPPER.readTree(content);
        }
        assertThat(bundle.get("fields")).isNotEmpty();
        List<String> fieldIds = new java.util.ArrayList<>();
        bundle.get("fields").forEach(field -> fieldIds.add(field.get("fieldId").asText()));
        assertThat(fieldIds).contains("meeting.title", "meeting.date");
        assertThat(bundle.get("excerpts")).isNotEmpty();
        String allExcerptText = String.join(" ", excerptTexts(bundle));
        assertThat(allExcerptText).contains("Priya Rao").contains("Alex Chen");
    }

    @Test
    void aDuplicateIdempotencyKeyWithTheSameRequestReturnsTheSameJob() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-generation-idempotent");
        long workspaceId = ensureWorkspace("subject-generation-idempotent").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-idempotent").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode flowing = findByDisplayName(templates, "Flowing meeting minutes");
        long documentId = createMinimalDocument(session, workspaceId, flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong());
        long sourceArtifactId = uploadAndFinalize(session, workspaceId, "The meeting was called to order.");

        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"sourceArtifactId\":" + sourceArtifactId + "}";
        JsonNode first = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session).with(csrf()).header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn());
        JsonNode second = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session).with(csrf()).header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn());

        assertThat(second.get("jobId").asLong()).isEqualTo(first.get("jobId").asLong());
        assertThat(second.get("commandId").asText()).isEqualTo(first.get("commandId").asText());
    }

    /**
     * Starting an extraction leaves two things behind that a reloaded page
     * needs: the document's own link to the source it was started from,
     * and a run record naming the job, the revision it was based on and
     * the model and prompt version it used -- both readable back through
     * the document. A replay under the same idempotency key adds neither
     * a second run nor a second link, and attaching the same file again
     * through the sources route returns the same link.
     */
    @Test
    void startingExtractionRecordsARunAndLinksItsSourceToTheDocument() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-generation-run");
        long workspaceId = ensureWorkspace("subject-generation-run").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-run").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode flowing = findByDisplayName(templates, "Flowing meeting minutes");
        long documentId = createMinimalDocument(session, workspaceId, flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong());
        long sourceArtifactId = uploadAndFinalize(session, workspaceId, "The meeting was called to order.");
        JsonNode documentBefore = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        long currentRevisionId = documentBefore.get("currentRevision").get("id").asLong();

        assertThat(readJson(mockMvc.perform(get(generationsPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn())).isEmpty();
        assertThat(readJson(mockMvc.perform(get(sourcesPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn())).isEmpty();

        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"sourceArtifactId\":" + sourceArtifactId + "}";
        long jobId = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session).with(csrf()).header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn()).get("jobId").asLong();
        mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session).with(csrf()).header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted());

        JsonNode runs = readJson(mockMvc.perform(get(generationsPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(runs).hasSize(1);
        JsonNode run = runs.get(0);
        assertThat(run.get("jobId").asLong()).isEqualTo(jobId);
        assertThat(run.get("documentId").asLong()).isEqualTo(documentId);
        assertThat(run.get("baseRevisionId").asLong()).isEqualTo(currentRevisionId);
        assertThat(run.get("sourceArtifactId").asLong()).isEqualTo(sourceArtifactId);
        assertThat(run.get("sourceSnapshotId").asLong()).isPositive();
        assertThat(run.get("modelName").asText()).isNotBlank();
        assertThat(run.get("promptVersion").asText()).isEqualTo("extraction-v1");
        assertThat(run.get("job").get("id").asLong()).isEqualTo(jobId);
        assertThat(run.get("job").get("state").asText()).isEqualTo("QUEUED");
        assertThat(run.get("resultArtifactId").isNull()).isTrue();

        JsonNode sources = readJson(mockMvc.perform(get(sourcesPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).get("id").asLong()).isEqualTo(run.get("sourceSnapshotId").asLong());
        assertThat(sources.get(0).get("artifactId").asLong()).isEqualTo(sourceArtifactId);
        assertThat(sources.get(0).get("displayFilename").asText()).isEqualTo("transcript.txt");
        assertThat(sources.get(0).get("kind").asText()).isEqualTo("ARTIFACT");

        JsonNode attachedAgain = readJson(mockMvc.perform(post(sourcesPath(workspaceId, documentId))
                        .cookie(session).with(csrf())
                        .contentType("application/json").content("{\"artifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(attachedAgain.get("id").asLong()).isEqualTo(sources.get(0).get("id").asLong());
        assertThat(readJson(mockMvc.perform(get(sourcesPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn())).hasSize(1);
    }

    /**
     * The staged-questions blob is keyed by job alone and overwritten by
     * every attempt, so the API only trusts one stamped with the job's
     * current attempt: a bundle from any other attempt is ignored. And
     * once an attempt's questions exist as rows, answering them all and
     * reloading returns nothing rather than a second copy of the same
     * questions -- the row count for the document stays exactly one.
     */
    @Test
    void pendingQuestionsAreMaterializedOncePerAttemptAndAStaleAttemptIsIgnored() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-generation-attempts");
        long workspaceId = ensureWorkspace("subject-generation-attempts").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-attempts").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode flowing = findByDisplayName(templates, "Flowing meeting minutes");
        long documentId = createMinimalDocument(session, workspaceId, flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong());
        long sourceArtifactId = uploadAndFinalize(session, workspaceId, "The meeting was called to order.");
        long jobId = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session).with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"sourceArtifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isAccepted())
                .andReturn()).get("jobId").asLong();
        long currentAttempt = Long.parseLong(jdbcJobColumn(workspaceId, userId, jobId, "fencing_token"));
        String questionsPath = generationsPath(workspaceId, documentId) + "/" + jobId + "/questions";

        stagePendingQuestions(workspaceId, jobId, currentAttempt + 7, "From a superseded attempt");
        assertThat(readJson(mockMvc.perform(get(questionsPath).cookie(session)).andExpect(status().isOk()).andReturn())).isEmpty();
        assertThat(questionService.allQuestions(workspaceId, userId, documentId)).isEmpty();

        stagePendingQuestions(workspaceId, jobId, currentAttempt, "Weekly Sync");
        JsonNode materialized = readJson(mockMvc.perform(get(questionsPath).cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(materialized).hasSize(1);
        assertThat(materialized.get(0).get("candidates").get(0).get("value").asText()).isEqualTo("Weekly Sync");
        long questionId = materialized.get(0).get("id").asLong();

        // Asking again before answering: the same open question, not another copy.
        JsonNode askedAgain = readJson(mockMvc.perform(get(questionsPath).cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(askedAgain).hasSize(1);
        assertThat(askedAgain.get(0).get("id").asLong()).isEqualTo(questionId);

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/questions/" + questionId + "/answer")
                        .cookie(session).with(csrf())
                        .contentType("application/json").content("{\"answerValue\":\"Weekly Sync\"}"))
                .andExpect(status().isOk());

        // A reload after everything was answered: nothing left to ask, and still exactly one row.
        assertThat(readJson(mockMvc.perform(get(questionsPath).cookie(session)).andExpect(status().isOk()).andReturn())).isEmpty();
        assertThat(questionService.allQuestions(workspaceId, userId, documentId)).hasSize(1);
    }

    private void stagePendingQuestions(long workspaceId, long jobId, long fencingToken, String candidate) throws Exception {
        String pendingJson = "{\"fencingToken\":" + fencingToken + ",\"questions\":[{\"fieldId\":\"meeting.title\","
                + "\"reason\":\"MISSING_REQUIRED\",\"candidates\":[{\"value\":\"" + candidate + "\",\"evidenceSpanIds\":[]}]}]}";
        blobStore.writeAndDigest(
                GenerationJobTypes.pendingQuestionsObjectKey(workspaceId, jobId),
                new ByteArrayInputStream(pendingJson.getBytes(StandardCharsets.UTF_8)),
                100_000);
    }

    private static String sourcesPath(long workspaceId, long documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/sources";
    }

    private static List<String> excerptTexts(JsonNode bundle) {
        List<String> texts = new java.util.ArrayList<>();
        bundle.get("excerpts").forEach(excerpt -> texts.add(excerpt.get("text").asText()));
        return texts;
    }

    @Autowired
    private javax.sql.DataSource dataSource;

    /**
     * {@code job} carries row-level security the same as every tenant
     * table (see {@code current_workspace_user_id()}); a raw connection
     * must set {@code app.current_user_id} itself, the same transaction-
     * local mechanism {@code TenantContext} gives every real repository,
     * and {@code SELECT set_config(..., true)}'s locality only holds
     * within one transaction, so autocommit must be off for both
     * statements to share it.
     */
    private String jdbcJobColumn(long workspaceId, long userId, long jobId, String column) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var setUser = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                setUser.setString(1, String.valueOf(userId));
                setUser.execute();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT " + column + " FROM job WHERE workspace_id = ? AND id = ?")) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new AssertionError("No job " + jobId + " visible in workspace " + workspaceId + " for user " + userId + ".");
                    }
                    return resultSet.getString(1);
                }
            } finally {
                connection.commit();
            }
        }
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
                + "\"initialRevisionReason\":\"Created for a generation-start test.\"}";
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

    private static String generationsPath(long workspaceId, long documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/generations";
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
