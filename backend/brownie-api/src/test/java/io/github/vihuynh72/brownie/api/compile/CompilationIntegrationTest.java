package io.github.vihuynh72.brownie.api.compile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplate;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
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
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole deterministic-compiler path end to end, through real
 * HTTP dispatch, a real Postgres/Azurite/ClamAV stack, and the real
 * pinned isolated-renderer Docker image -- upload a built-in blank
 * fixture, extract it, bind and activate a template from it, create a
 * document instance with typed content, then compile that exact revision
 * into a downloaded-ready DOCX and PDF. No model call is made anywhere in
 * this path, matching this phase's own "without AI" requirement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class CompilationIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-compilation-integration";

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

    @Test
    void compilesARealBuiltInTemplateIntoDownloadableDocxAndPdfWithPassingIntegrityChecks() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-compile-happy");
        long workspaceId = ensureWorkspace("subject-compile-happy").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();

        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Spring Budget Planning minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Spring Budget Planning"},
                    "meeting.organization": {"type": "TEXT", "cardinality": "SCALAR", "value": "Riverside Robotics Club"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-03-05"},
                    "action.item.task": {"type": "TEXT", "cardinality": "REPEATED", "values": ["Reserve the van"]},
                    "action.item.owner": {"type": "TEXT", "cardinality": "REPEATED", "values": ["Alex Chen"]},
                    "action.item.due": {"type": "DATE", "cardinality": "REPEATED", "values": ["2026-03-10"]}
                  },
                  "initialRevisionReason": "initial draft"
                }
                """.formatted(templateId, templateVersionId);

        JsonNode created = readJson(mockMvc.perform(post(documentsPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn());
        long documentId = created.get("id").asLong();
        long revisionId = created.get("currentRevision").get("id").asLong();

        JsonNode manifest = readJson(mockMvc.perform(
                        post(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + revisionId + "/compile")
                                .cookie(session)
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(manifest.get("documentId").asLong()).isEqualTo(documentId);
        assertThat(manifest.get("revisionId").asLong()).isEqualTo(revisionId);
        assertThat(manifest.get("templateVersionId").asLong()).isEqualTo(templateVersionId);
        assertThat(manifest.get("allIntegrityChecksPassed").asBoolean()).isTrue();
        assertThat(manifest.get("integrityFindings")).isNotEmpty();
        for (JsonNode finding : manifest.get("integrityFindings")) {
            assertThat(finding.get("foundInDocx").asBoolean()).as(finding.toString()).isTrue();
            assertThat(finding.get("foundInPdf").asBoolean()).as(finding.toString()).isTrue();
        }

        long docxArtifactId = manifest.get("docxArtifactId").asLong();
        long pdfArtifactId = manifest.get("pdfArtifactId").asLong();
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/uploads/" + docxArtifactId + "/download").cookie(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/uploads/" + pdfArtifactId + "/download").cookie(session))
                .andExpect(status().isOk());

        JsonNode latest = readJson(mockMvc.perform(
                        get(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + revisionId + "/compilation")
                                .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(latest.get("id").asLong()).isEqualTo(manifest.get("id").asLong());
    }

    @Test
    void emptyActionItemsStillPassIntegrityChecksAgainstTheExplanatoryLine() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-compile-empty");
        long workspaceId = ensureWorkspace("subject-compile-empty").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();

        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Officer Check-in minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Officer Check-in"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-03-05"}
                  },
                  "initialRevisionReason": "initial draft"
                }
                """.formatted(templateId, templateVersionId);

        JsonNode created = readJson(mockMvc.perform(post(documentsPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn());
        long documentId = created.get("id").asLong();
        long revisionId = created.get("currentRevision").get("id").asLong();

        JsonNode manifest = readJson(mockMvc.perform(
                        post(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + revisionId + "/compile")
                                .cookie(session)
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(manifest.get("allIntegrityChecksPassed").asBoolean()).isTrue();
    }

    @Test
    void editingAFieldProducesANewRevisionWhoseCompiledArtifactsReflectTheEditAndNotTheOriginal() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-compile-edit");
        long workspaceId = ensureWorkspace("subject-compile-edit").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();

        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "September minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "September minutes"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-09-01"}
                  },
                  "initialRevisionReason": "initial draft"
                }
                """.formatted(templateId, templateVersionId);
        JsonNode created = readJson(mockMvc.perform(post(documentsPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn());
        long documentId = created.get("id").asLong();
        long firstRevisionId = created.get("currentRevision").get("id").asLong();

        JsonNode firstManifest = readJson(mockMvc.perform(
                        post(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + firstRevisionId + "/compile")
                                .cookie(session)
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(firstManifest.get("integrityFindings").toString()).contains("September minutes");

        String editBody = """
                {
                  "expectedRevisionId": %d,
                  "edits": [
                    {"operation": "SET", "fieldId": "meeting.title", "value": {"type": "TEXT", "cardinality": "SCALAR", "value": "October minutes"}}
                  ],
                  "editReason": "corrected meeting title"
                }
                """.formatted(firstRevisionId);
        JsonNode editedRevision = readJson(mockMvc.perform(patch(documentsPath(workspaceId) + "/" + documentId + "/content")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "edit-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(editBody))
                .andExpect(status().isOk())
                .andReturn());
        long secondRevisionId = editedRevision.get("id").asLong();
        assertThat(secondRevisionId).isNotEqualTo(firstRevisionId);

        JsonNode secondManifest = readJson(mockMvc.perform(
                        post(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + secondRevisionId + "/compile")
                                .cookie(session)
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(secondManifest.get("allIntegrityChecksPassed").asBoolean()).isTrue();
        assertThat(secondManifest.get("integrityFindings").toString()).contains("October minutes");
        assertThat(secondManifest.get("integrityFindings").toString()).doesNotContain("September minutes");
        assertThat(secondManifest.get("docxArtifactId").asLong()).isNotEqualTo(firstManifest.get("docxArtifactId").asLong());
        assertThat(secondManifest.get("pdfArtifactId").asLong()).isNotEqualTo(firstManifest.get("pdfArtifactId").asLong());

        // The original revision's own already-compiled record is untouched by the later edit.
        JsonNode firstAgain = readJson(mockMvc.perform(
                        get(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + firstRevisionId + "/compilation")
                                .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(firstAgain.get("id").asLong()).isEqualTo(firstManifest.get("id").asLong());
        assertThat(firstAgain.get("integrityFindings").toString()).contains("September minutes");
    }

    @Test
    void longDecisionAndTaskTextSurvivesDocxAndPdfConversionWithoutTruncation() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-compile-long");
        long workspaceId = ensureWorkspace("subject-compile-long").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String longDecision = "The club voted to approve the expanded spring budget, including the newly added "
                + "exhibition event in the neighboring district, after extensive discussion of transportation costs, "
                + "insurance requirements, and the volunteer schedule needed to staff every shift across the full "
                + "three-day event.";
        String longTask = "Coordinate the updated transportation and lodging plan, including the newly added "
                + "exhibition event in the neighboring district, with the school's activities office and confirm "
                + "the final volunteer roster before the deposit deadline.";

        String createBody = """
                {
                  "title": "Long-content minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Long-content minutes"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-03-05"},
                    "meeting.decisions": {"type": "TEXT", "cardinality": "SCALAR", "value": "%s"},
                    "action.item.task": {"type": "TEXT", "cardinality": "REPEATED", "values": ["%s"]},
                    "action.item.owner": {"type": "TEXT", "cardinality": "REPEATED", "values": ["Jordan Lee"]},
                    "action.item.due": {"type": "DATE", "cardinality": "REPEATED", "values": ["2026-03-20"]}
                  },
                  "initialRevisionReason": "initial draft"
                }
                """.formatted(templateId, templateVersionId, longDecision, longTask);

        JsonNode created = readJson(mockMvc.perform(post(documentsPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn());
        long documentId = created.get("id").asLong();
        long revisionId = created.get("currentRevision").get("id").asLong();

        JsonNode manifest = readJson(mockMvc.perform(
                        post(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + revisionId + "/compile")
                                .cookie(session)
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(manifest.get("allIntegrityChecksPassed").asBoolean()).isTrue();
        assertThat(manifest.get("integrityFindings").toString()).contains("exhibition event in the neighboring district");
    }

    /**
     * The "conflicting" fixture case for this deterministic phase: a
     * document whose parallel repeated fields (task/owner/due, one action
     * item per index across all three) disagree on item count. Nothing in
     * the generic, template-agnostic content validator catches this --
     * only the filler actually knows these three fields form one group --
     * so this proves the real compiler rejects it outright, with no
     * partial DOCX/PDF artifact and no compilation record left behind,
     * rather than silently misaligning owners against the wrong tasks.
     */
    @Test
    void conflictingRepeatedFieldLengthsAreRejectedWithNoArtifactOrManifestLeftBehind() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-compile-conflict");
        long workspaceId = ensureWorkspace("subject-compile-conflict").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Conflicting action items",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Conflicting action items"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-03-05"},
                    "action.item.task": {"type": "TEXT", "cardinality": "REPEATED", "values": ["Task one", "Task two"]},
                    "action.item.owner": {"type": "TEXT", "cardinality": "REPEATED", "values": ["Only one owner"]}
                  },
                  "initialRevisionReason": "initial draft"
                }
                """.formatted(templateId, templateVersionId);

        JsonNode created = readJson(mockMvc.perform(post(documentsPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn());
        long documentId = created.get("id").asLong();
        long revisionId = created.get("currentRevision").get("id").asLong();

        JsonNode problem = readJson(mockMvc.perform(
                        post(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + revisionId + "/compile")
                                .cookie(session)
                                .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andReturn());
        assertThat(problem.get("code").asText()).isEqualTo("TEMPLATE_FILL_MISMATCHED_REPEATED_LENGTHS");

        mockMvc.perform(get(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + revisionId + "/compilation")
                        .cookie(session))
                .andExpect(status().isNotFound());
    }

    /**
     * The IDOR-shaped test every other tenant-scoped resource in this
     * codebase already has (see e.g. {@code ArtifactUploadIntegrationTest}
     * 's own aMemberCannotReadAnotherWorkspacesArtifactByIdEvenUnderTheirOwnAuthorizedWorkspacePath):
     * a real, authenticated member of their OWN workspace, passing that
     * workspace's own authorization check, must not be able to reach
     * another workspace's document/revision by supplying its ID under
     * their own workspace path.
     */
    @Test
    void aMemberCannotCompileOrReadAnotherWorkspacesDocumentUnderTheirOwnAuthorizedWorkspacePath() throws Exception {
        Cookie ownerSession = loginAndGetSessionCookie("subject-compile-idor-owner");
        long ownerWorkspaceId = ensureWorkspace("subject-compile-idor-owner").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(ownerSession, ownerWorkspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Owner-only minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Owner-only minutes"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-03-05"}
                  },
                  "initialRevisionReason": "initial draft"
                }
                """.formatted(templateId, templateVersionId);
        JsonNode created = readJson(mockMvc.perform(post(documentsPath(ownerWorkspaceId))
                        .cookie(ownerSession)
                        .with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn());
        long ownerDocumentId = created.get("id").asLong();
        long ownerRevisionId = created.get("currentRevision").get("id").asLong();

        Cookie intruderSession = loginAndGetSessionCookie("subject-compile-idor-intruder");
        long intruderWorkspaceId = ensureWorkspace("subject-compile-idor-intruder").id();

        mockMvc.perform(post(documentsPath(intruderWorkspaceId) + "/" + ownerDocumentId + "/revisions/" + ownerRevisionId + "/compile")
                        .cookie(intruderSession)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(documentsPath(intruderWorkspaceId) + "/" + ownerDocumentId + "/revisions/" + ownerRevisionId + "/compilation")
                        .cookie(intruderSession))
                .andExpect(status().isNotFound());

        // The owner's own path still works -- this is workspace scoping, not a broken route.
        mockMvc.perform(post(documentsPath(ownerWorkspaceId) + "/" + ownerDocumentId + "/revisions/" + ownerRevisionId + "/compile")
                        .cookie(ownerSession)
                        .with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    void compilingWithoutAuthenticationIsRejected() throws Exception {
        var identity = userIdentityRepository.recordLogin(ISSUER, "subject-compile-unauth", null, null);
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(identity.id()).id();

        mockMvc.perform(post(documentsPath(workspaceId) + "/1/revisions/1/compile").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    private long lastCreatedTemplateId;

    private long activateBuiltInTemplate(Cookie session, long workspaceId, BuiltInMinutesTemplate template) throws Exception {
        byte[] blank = Files.readAllBytes(fixturePath(template.templateFixturePath()));
        long artifactId = uploadAndFinalize(session, workspaceId, blank, "template.docx");
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/artifacts/" + artifactId + "/extraction")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isOk());

        JsonNode createdTemplate = readJson(mockMvc.perform(post(templatesPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"" + template.displayName() + "\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        long templateId = createdTemplate.get("template").get("id").asLong();
        lastCreatedTemplateId = templateId;

        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(bindingsRequestBody(template.fields())))
                .andExpect(status().isOk());

        JsonNode activated = readJson(mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isCreated())
                .andReturn());
        return activated.get("id").asLong();
    }

    private static String bindingsRequestBody(List<FieldDefinition> fields) {
        StringBuilder body = new StringBuilder("{\"expectedVersionNumber\":1,\"fields\":[");
        for (int i = 0; i < fields.size(); i++) {
            FieldDefinition field = fields.get(i);
            String tag = ((FieldBindingTarget.ContentControlTag) field.binding()).tag();
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"fieldId\":\"").append(field.fieldId())
                    .append("\",\"type\":\"").append(field.type())
                    .append("\",\"cardinality\":\"").append(field.cardinality())
                    .append("\",\"requiredness\":\"").append(field.requiredness())
                    .append("\",\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"").append(tag).append("\"}}");
        }
        body.append("]}");
        return body.toString();
    }

    private static String documentsPath(long workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents";
    }

    private static String templatesPath(long workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/templates";
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

    private static Path fixturePath(String repositoryRelativePath) {
        Path path = repositoryRoot().resolve(repositoryRelativePath);
        assertThat(Files.isRegularFile(path)).as("missing fixture: " + path).isTrue();
        return path;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures/public/templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from the test working directory");
    }
}
