package io.github.vihuynh72.brownie.api.export;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * This phase's own closing sweep: the whole validate/approve/export
 * pipeline run together against a held-out template and real adversarial
 * scenarios, re-confirming this phase's own gate directly rather than
 * only each task's own narrower slice of it -- "no blocking finding
 * produces a final-approved export; stale approval fails; wrong or
 * partial artifacts never appear as fully complete."
 *
 * <p>One real, honest boundary named here rather than forced: a genuinely
 * tampered protected region is not reachable through this codebase's own
 * real HTTP surface at all -- a caller only ever supplies typed field
 * values, never raw document structure, so the only way {@code
 * LAYOUT_PROTECTED_REGION_CHANGE} could fire in practice is a bug in the
 * filler itself, not adversarial user input. That detection logic is
 * already proven directly by {@code LayoutComparatorTest}'s own unit
 * cases against hand-built graphs mirroring the real filler's tag-rewrite
 * convention; manufacturing a fake HTTP-reachable tamper here would prove
 * nothing this phase does not already know. What this sweep proves
 * instead, and what is actually reachable through real use, is that
 * legitimate content variation -- many action items, zero action items,
 * a different built-in template altogether -- never produces a false
 * positive, since a false block on valid data is the more likely and more
 * damaging real-world failure mode.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class ExportValidationAdversarialIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-export-validation-adversarial";

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

    /**
     * The held-out template: table-led-meeting-minutes, never exercised
     * end to end by any of this phase's own earlier per-task tests (they
     * all used flowing-meeting-minutes). Five action items against the
     * table-led template's own two-sample-item baseline -- more than
     * either earlier real-fixture run ever exercised -- proves the
     * repeated-row exclusion (the second real bug that earlier work's own
     * journal entry names) holds for a genuinely different row count, not
     * just the one count that happened to be tested before.
     */
    @Test
    void theHeldOutTableLedTemplateWithManyActionItemsExportsCleanly() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-adversarial-many-items");
        long workspaceId = ensureWorkspace("subject-adversarial-many-items").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Officer meeting with many action items",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Officer meeting with many action items"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-04-02"},
                    "action.item.task": {"type": "TEXT", "cardinality": "REPEATED", "values": \
                ["Book venue", "Order supplies", "Confirm judges", "Print programs", "Arrange transport"]},
                    "action.item.owner": {"type": "TEXT", "cardinality": "REPEATED", "values": \
                ["Alex", "Priya", "Jordan", "Sam", "Taylor"]},
                    "action.item.due": {"type": "DATE", "cardinality": "REPEATED", "values": \
                ["2026-04-10", "2026-04-11", "2026-04-12", "2026-04-13", "2026-04-14"]}
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

        JsonNode manifest = validate(session, workspaceId, documentId, revisionId);
        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean())
                .as(manifest.toString())
                .isFalse();
        for (JsonNode finding : manifest.get("findings")) {
            assertThat(finding.get("severity").asText()).as(finding.toString()).isNotEqualTo("BLOCKING");
        }

        approve(session, workspaceId, documentId, manifest.get("id").asLong());
        JsonNode receipt = export(session, workspaceId, documentId);
        assertThat(receipt.get("isCompletePair").asBoolean()).isTrue();
    }

    /** Zero action items -- the empty-repeated-group explanatory line this phase's own second task found and fixed -- must remain informational only and never block approval or export. */
    @Test
    void zeroActionItemsProducesOnlyInformationalFindingsAndStillExports() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-adversarial-zero-items");
        long workspaceId = ensureWorkspace("subject-adversarial-zero-items").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Officer check-in, no action items",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Officer check-in, no action items"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-04-02"}
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

        JsonNode manifest = validate(session, workspaceId, documentId, revisionId);
        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean()).isFalse();

        approve(session, workspaceId, documentId, manifest.get("id").asLong());
        JsonNode receipt = export(session, workspaceId, documentId);
        assertThat(receipt.get("isCompletePair").asBoolean()).isTrue();
    }

    /** This phase's own gate, stated literally: a blocking finding must be refused at approval, and export must be unreachable without one. */
    @Test
    void aBlockingFindingIsRefusedAtApprovalAndExportIsNeverReachedWithoutOne() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-adversarial-blocking-gate");
        long workspaceId = ensureWorkspace("subject-adversarial-blocking-gate").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Missing date, blocking",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Missing date, blocking"}
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

        JsonNode manifest = validate(session, workspaceId, documentId, revisionId);
        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean()).isTrue();

        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export-approval")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"BOTH\"}"))
                .andExpect(status().isUnprocessableEntity());

        // No approval was ever recorded, so export itself is unreachable.
        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    /** Editing the document again after approval (without re-validating) must invalidate that approval for export, not just for a fresh approval attempt. */
    @Test
    void editingTheDocumentAfterApprovalInvalidatesItForExport() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-adversarial-edit-after-approval");
        long workspaceId = ensureWorkspace("subject-adversarial-edit-after-approval").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Approved then edited",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Approved then edited"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-04-02"}
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

        JsonNode manifest = validate(session, workspaceId, documentId, revisionId);
        approve(session, workspaceId, documentId, manifest.get("id").asLong());

        long validatedRevisionId = manifest.get("revisionId").asLong();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(documentsPath(workspaceId) + "/" + documentId + "/content")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "edit-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {
                                  "expectedRevisionId": %d,
                                  "edits": [
                                    {"operation": "SET", "fieldId": "meeting.title", "value": {"type": "TEXT", "cardinality": "SCALAR", "value": "Changed after approval"}}
                                  ],
                                  "editReason": "changed after approval"
                                }
                                """.formatted(validatedRevisionId)))
                .andExpect(status().isOk());

        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isPreconditionFailed());
    }

    private JsonNode validate(Cookie session, long workspaceId, long documentId, long expectedRevisionId) throws Exception {
        return readJson(mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/validate")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "validate-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + expectedRevisionId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private void approve(Cookie session, long workspaceId, long documentId, long validationManifestId) throws Exception {
        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export-approval")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + validationManifestId + ",\"format\":\"BOTH\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode export(Cookie session, long workspaceId, long documentId) throws Exception {
        return readJson(mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private String documentsPath(long workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents";
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
