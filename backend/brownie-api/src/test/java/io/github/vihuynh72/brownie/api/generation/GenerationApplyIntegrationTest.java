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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the last real step of the async generation path for real: given a
 * finished job's own published result (fabricated directly at the database
 * and blob-storage layer, the same "no worker runs in an API-module test"
 * reasoning {@code GenerationStartIntegrationTest} already states, since a
 * real worker process is a different module this one must never depend
 * on), turning it into a real {@link
 * io.github.vihuynh72.brownie.core.revision.PatchProposal} via {@code
 * POST .../generations/{jobId}/apply}, then actually applying it onto the
 * document's real current revision via {@code POST
 * .../patch-proposals/{proposalId}/accept} -- the document's own content
 * genuinely changes, read back through the ordinary document GET route.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class GenerationApplyIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-generation-apply";

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
    void applyingAFinishedJobSResultCreatesAndAcceptsARealPatchProposal() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-generation-apply");
        long workspaceId = ensureWorkspace("subject-generation-apply").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-apply").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);

        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode flowing = findByDisplayName(templates, "Flowing meeting minutes");
        long templateId = flowing.get("id").asLong();
        long templateVersionId = flowing.get("currentActiveVersionId").asLong();

        long documentId = createMinimalDocument(session, workspaceId, templateId, templateVersionId);
        long sourceArtifactId = uploadAndFinalize(session, workspaceId, "The meeting was called to order.");

        JsonNode started = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isAccepted())
                .andReturn());
        long jobId = started.get("jobId").asLong();

        // No worker runs in this module's own tests (see this class's own
        // javadoc); fabricate exactly the artifact a real worker would have
        // published for this job, at the same database/blob-storage layer
        // GenerationExtractionJobProcessor itself writes to.
        String resultJson = """
                {"scalarCandidates":{\
                "meeting.title":{"fieldId":"meeting.title","value":"Weekly Sync","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "meeting.date":{"fieldId":"meeting.date","value":"2026-03-12","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null}},\
                "repeatedItems":[]}""";
        fabricatePublishedResult(workspaceId, jobId, resultJson);

        JsonNode proposal = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId) + "/" + jobId + "/apply")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        long proposalId = proposal.get("id").asLong();
        assertThat(proposal.get("documentId").asLong()).isEqualTo(documentId);
        assertThat(proposal.get("proposedValues").get("meeting.title").get("value").asText()).isEqualTo("Weekly Sync");
        assertThat(proposal.get("proposedValues").get("meeting.date").get("value").asText()).isEqualTo("2026-03-12");
        assertThat(proposal.get("proposedValues").get("meeting.date").get("type").asText()).isEqualTo("DATE");

        JsonNode documentBeforeAccept = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        long currentRevisionId = documentBeforeAccept.get("currentRevision").get("id").asLong();

        JsonNode accepted = readJson(mockMvc.perform(
                        post("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/patch-proposals/" + proposalId + "/accept")
                                .cookie(session)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"expectedRevisionId\":" + currentRevisionId + "}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(accepted.get("applied").asBoolean()).isTrue();
        assertThat(accepted.get("revision").get("fields").get("meeting.title").get("value").asText()).isEqualTo("Weekly Sync");

        JsonNode documentAfterAccept = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(documentAfterAccept.get("currentRevision").get("fields").get("meeting.title").get("value").asText())
                .isEqualTo("Weekly Sync");
        assertThat(documentAfterAccept.get("currentRevision").get("fields").get("meeting.date").get("value").asText())
                .isEqualTo("2026-03-12");
    }

    /**
     * The whole point of the action-items table: a result carrying repeated
     * rows must reach the exported file. Two complete rows are proposed as
     * the template's three parallel repeated fields, accepted onto the
     * document, and then read back out of the real DOCX that validation
     * compiles -- while the one row whose due date the model could not
     * determine is reported by name in the proposal instead of vanishing.
     * Before this, the exported minutes said "No action items recorded."
     * over exactly this kind of result.
     */
    @Test
    void applyingAResultWithActionItemsProposesThemAndTheyReachTheExportedDocx() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-generation-apply-items");
        long workspaceId = ensureWorkspace("subject-generation-apply-items").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-apply-items").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        JsonNode flowing = findByDisplayName(
                readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                        .andExpect(status().isOk())
                        .andReturn()),
                "Flowing meeting minutes");
        long documentId = createMinimalDocument(session, workspaceId, flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong());
        long sourceArtifactId = uploadAndFinalize(session, workspaceId, "Alex agreed to wire the robot by March 12.");
        long jobId = startExtraction(session, workspaceId, documentId, sourceArtifactId);

        String resultJson = """
                {"scalarCandidates":{\
                "meeting.title":{"fieldId":"meeting.title","value":"Weekly Robotics Club Sync","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "meeting.date":{"fieldId":"meeting.date","value":"2026-03-05","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null}},\
                "repeatedItems":[\
                {"fields":{\
                "action.item.task":{"fieldId":"action.item.task","value":"finish wiring the practice robot","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "action.item.owner":{"fieldId":"action.item.owner","value":"Alex Chen","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "action.item.due":{"fieldId":"action.item.due","value":"2026-03-12","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null}}},\
                {"fields":{\
                "action.item.task":{"fieldId":"action.item.task","value":"confirm the van reservation","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "action.item.owner":{"fieldId":"action.item.owner","value":"Jose Nunez","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "action.item.due":{"fieldId":"action.item.due","value":"2026-03-10","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null}}},\
                {"fields":{\
                "action.item.task":{"fieldId":"action.item.task","value":"order the new batteries","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "action.item.owner":{"fieldId":"action.item.owner","value":"Priya Rao","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
                "action.item.due":{"fieldId":"action.item.due","value":null,"evidenceSpanIds":[],"unresolved":true,"ambiguityReason":"No due date was stated."}}}]}""";
        fabricatePublishedResult(workspaceId, jobId, resultJson);

        JsonNode proposal = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId) + "/" + jobId + "/apply")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        JsonNode tasks = proposal.get("proposedValues").get("action.item.task");
        assertThat(tasks.get("cardinality").asText()).isEqualTo("REPEATED");
        assertThat(tasks.get("value").isNull()).isTrue();
        assertThat(texts(tasks.get("values"))).containsExactly("finish wiring the practice robot", "confirm the van reservation");
        assertThat(texts(proposal.get("proposedValues").get("action.item.owner").get("values"))).containsExactly("Alex Chen", "Jose Nunez");
        assertThat(texts(proposal.get("proposedValues").get("action.item.due").get("values"))).containsExactly("2026-03-12", "2026-03-10");
        assertThat(proposal.get("proposedValues").get("action.item.due").get("type").asText()).isEqualTo("DATE");
        assertThat(proposal.get("proposedRepeatedItemCount").asInt()).isEqualTo(2);
        JsonNode skipped = proposal.get("skippedRepeatedItems");
        assertThat(skipped).hasSize(1);
        assertThat(skipped.get(0).get("itemIndex").asInt()).isEqualTo(2);
        assertThat(texts(skipped.get(0).get("unresolvedFieldIds"))).containsExactly("action.item.due");
        assertThat(skipped.get(0).get("description").asText()).isEqualTo("order the new batteries");

        long currentRevisionId = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId).cookie(session))
                .andExpect(status().isOk())
                .andReturn()).get("currentRevision").get("id").asLong();
        JsonNode accepted = readJson(mockMvc.perform(
                        post("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/patch-proposals/" + proposal.get("id").asLong() + "/accept")
                                .cookie(session)
                                .with(csrf())
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content("{\"expectedRevisionId\":" + currentRevisionId + "}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(accepted.get("applied").asBoolean()).isTrue();
        assertThat(accepted.get("fieldStatuses").get("action.item.task").asText()).isEqualTo("CLEAN");
        JsonNode acceptedFields = accepted.get("revision").get("fields");
        assertThat(texts(acceptedFields.get("action.item.task").get("values"))).containsExactly("finish wiring the practice robot", "confirm the van reservation");
        assertThat(acceptedFields.get("action.item.task").get("itemFieldStates")).hasSize(2);
        long acceptedRevisionId = accepted.get("revision").get("id").asLong();

        JsonNode manifest = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/validate")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + acceptedRevisionId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean()).isFalse();
        byte[] docx = mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/uploads/" + manifest.get("docxArtifactId").asLong() + "/download")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        String exportedText = documentXmlText(docx);
        assertThat(exportedText).contains("Weekly Robotics Club Sync");
        assertThat(exportedText).contains("finish wiring the practice robot", "Alex Chen", "March 12, 2026");
        assertThat(exportedText).contains("confirm the van reservation", "Jose Nunez", "March 10, 2026");
        assertThat(exportedText).doesNotContain("No action items recorded.");
        assertThat(exportedText).doesNotContain("order the new batteries");
    }

    /**
     * The pending-questions and resolved-answers objects are keyed by the
     * job ID alone -- a global sequence -- so every route that reads or
     * writes one must first prove the job belongs to the caller's own
     * workspace and to the document named in the URL. Guessing a job ID
     * from another workspace, or naming one of your own documents that the
     * job was never about, gets a plain 404 and touches nothing: no
     * question is persisted anywhere, no answers blob is written, and the
     * legitimate owner still reads its own questions afterwards exactly as
     * if the attempt had never happened.
     */
    @Test
    void generationQuestionsResumeAndApplyAreRefusedForAJobOfAnotherWorkspaceOrAnotherDocument() throws Exception {
        Cookie ownerSession = loginAndGetSessionCookie("subject-generation-owner");
        long ownerWorkspaceId = ensureWorkspace("subject-generation-owner").id();
        long ownerUserId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-owner").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(ownerWorkspaceId, ownerUserId);
        JsonNode ownerTemplate = findByDisplayName(
                readJson(mockMvc.perform(get("/api/v1/workspaces/" + ownerWorkspaceId + "/templates").cookie(ownerSession))
                        .andExpect(status().isOk())
                        .andReturn()),
                "Flowing meeting minutes");
        long ownerTemplateId = ownerTemplate.get("id").asLong();
        long ownerTemplateVersionId = ownerTemplate.get("currentActiveVersionId").asLong();
        long ownerDocumentId = createMinimalDocument(ownerSession, ownerWorkspaceId, ownerTemplateId, ownerTemplateVersionId);
        long ownerOtherDocumentId = createMinimalDocument(ownerSession, ownerWorkspaceId, ownerTemplateId, ownerTemplateVersionId);
        long ownerSourceId = uploadAndFinalize(ownerSession, ownerWorkspaceId, "A private transcript.");
        long jobId = startExtraction(ownerSession, ownerWorkspaceId, ownerDocumentId, ownerSourceId);

        // Exactly what the worker stages when it leaves a job waiting for
        // input, stamped with the job's current attempt so the API treats
        // it as live.
        String secret = "Confidential candidate only workspace A should ever see";
        String pendingJson = "{\"fencingToken\":" + jdbcFencingToken(jobId) + ",\"questions\":[{\"fieldId\":\"meeting.title\","
                + "\"reason\":\"MISSING_REQUIRED\",\"candidates\":[{\"value\":\"" + secret + "\",\"evidenceSpanIds\":[]}]}]}";
        blobStore.writeAndDigest(
                GenerationJobTypes.pendingQuestionsObjectKey(ownerWorkspaceId, jobId),
                new ByteArrayInputStream(pendingJson.getBytes(StandardCharsets.UTF_8)),
                100_000);

        Cookie intruderSession = loginAndGetSessionCookie("subject-generation-intruder");
        long intruderWorkspaceId = ensureWorkspace("subject-generation-intruder").id();
        long intruderUserId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-generation-intruder").orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(intruderWorkspaceId, intruderUserId);
        JsonNode intruderTemplate = findByDisplayName(
                readJson(mockMvc.perform(get("/api/v1/workspaces/" + intruderWorkspaceId + "/templates").cookie(intruderSession))
                        .andExpect(status().isOk())
                        .andReturn()),
                "Flowing meeting minutes");
        long intruderDocumentId = createMinimalDocument(
                intruderSession, intruderWorkspaceId, intruderTemplate.get("id").asLong(), intruderTemplate.get("currentActiveVersionId").asLong());

        // Another workspace, guessing the job ID: refused before any blob is read or written.
        mockMvc.perform(get(generationsPath(intruderWorkspaceId, intruderDocumentId) + "/" + jobId + "/questions").cookie(intruderSession))
                .andExpect(status().isNotFound());
        // ...and it cannot list the owner's runs or sources through its own workspace either.
        mockMvc.perform(get(generationsPath(intruderWorkspaceId, ownerDocumentId)).cookie(intruderSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/workspaces/" + intruderWorkspaceId + "/documents/" + ownerDocumentId + "/sources").cookie(intruderSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(generationsPath(intruderWorkspaceId, intruderDocumentId) + "/" + jobId + "/resume")
                        .cookie(intruderSession)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(generationsPath(intruderWorkspaceId, intruderDocumentId) + "/" + jobId + "/apply")
                        .cookie(intruderSession)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(questionService.allQuestions(intruderWorkspaceId, intruderUserId, intruderDocumentId)).isEmpty();
        assertThat(blobStore.sizeOf(GenerationJobTypes.resolvedAnswersObjectKey(ownerWorkspaceId, jobId))).isEmpty();
        assertThat(blobStore.sizeOf(GenerationJobTypes.resolvedAnswersObjectKey(intruderWorkspaceId, jobId))).isEmpty();

        // The same workspace, but a document the job was never about: also refused, nothing persisted.
        mockMvc.perform(get(generationsPath(ownerWorkspaceId, ownerOtherDocumentId) + "/" + jobId + "/questions").cookie(ownerSession))
                .andExpect(status().isNotFound());
        assertThat(questionService.allQuestions(ownerWorkspaceId, ownerUserId, ownerOtherDocumentId)).isEmpty();

        // The legitimate owner, through the document the job is really about: the staged questions are theirs.
        JsonNode questions = readJson(mockMvc.perform(get(generationsPath(ownerWorkspaceId, ownerDocumentId) + "/" + jobId + "/questions").cookie(ownerSession))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).get("fieldId").asText()).isEqualTo("meeting.title");
        assertThat(questions.get(0).get("candidates").get(0).get("value").asText()).isEqualTo(secret);
    }

    private long startExtraction(Cookie session, long workspaceId, long documentId, long sourceArtifactId) throws Exception {
        JsonNode started = readJson(mockMvc.perform(post(generationsPath(workspaceId, documentId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isAccepted())
                .andReturn());
        return started.get("jobId").asLong();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    /** The body text of {@code word/document.xml} inside the exported package, with its XML tags stripped -- the same inspection the browser journey performs with {@code unzip}. */
    private static String documentXmlText(byte[] docx) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8).replaceAll("<[^>]*>", "");
                }
            }
        }
        throw new AssertionError("The exported DOCX has no word/document.xml part.");
    }

    /**
     * Replicates, via raw JDBC as the schema-owning migration role, exactly
     * the rows {@code worker_publish_staged_output} itself would insert
     * for a real completed job -- bypassing that SECURITY DEFINER function
     * entirely (it only accepts the {@code brownie_worker} login) since
     * this test's own job is proving what the API does with an already-
     * published result, not re-proving the worker's own publish path,
     * which {@code GenerationExtractionJobProcessorIntegrationTest}
     * (brownie-worker) already does.
     */
    private void fabricatePublishedResult(long workspaceId, long jobId, String resultJson) throws Exception {
        String objectKey = "generation-result-fixture/" + UUID.randomUUID() + ".json";
        blobStore.writeNewAndDigest(objectKey, new ByteArrayInputStream(resultJson.getBytes(StandardCharsets.UTF_8)), 1_000_000);
        String sha256 = sha256Hex(resultJson);
        int byteCount = resultJson.getBytes(StandardCharsets.UTF_8).length;

        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD)) {
            long stagedOutputId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO job_staged_output (workspace_id, job_id, worker_id, fencing_token, output_kind, object_key, sha256, byte_count, expires_at)
                    VALUES (?, ?, 'apply-test-fixture', 1, 'extraction-result', ?, ?, ?, now() + interval '1 hour')
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
                statement.setString(3, objectKey);
                statement.setString(4, sha256);
                statement.setInt(5, byteCount);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    stagedOutputId = result.getLong(1);
                }
            }

            long artifactId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO artifact (workspace_id, blob_key, status, byte_count, sha256, detected_media_type, finalized_at)
                    VALUES (?, ?, 'READY', ?, ?, 'PLAIN_TEXT', now())
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setString(2, objectKey);
                statement.setInt(3, byteCount);
                statement.setString(4, sha256);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    artifactId = result.getLong(1);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO job_output_artifact (workspace_id, job_id, output_kind, staged_output_id, artifact_id)
                    VALUES (?, ?, 'extraction-result', ?, ?)
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
                statement.setLong(3, stagedOutputId);
                statement.setLong(4, artifactId);
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement("UPDATE job SET state = 'SUCCEEDED' WHERE id = ?")) {
                statement.setLong(1, jobId);
                statement.executeUpdate();
            }
        }
    }

    /** The job's current fencing token (0 until a worker's first claim), read as the schema owner so no tenant context is needed. */
    private long jdbcFencingToken(long jobId) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = connection.prepareStatement("SELECT fencing_token FROM job WHERE id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("No job " + jobId + ".");
                }
                return result.getLong(1);
            }
        }
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
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
                + "\"initialRevisionReason\":\"Created for a generation-apply test.\"}";
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
