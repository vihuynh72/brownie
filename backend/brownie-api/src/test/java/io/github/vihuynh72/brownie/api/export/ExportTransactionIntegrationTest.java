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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole export transaction end to end through real HTTP
 * dispatch, a real Postgres/Azurite/ClamAV stack, and the real
 * Docker-isolated LibreOffice renderer: validate a complete revision,
 * approve it, export it, and confirm the receipt names the exact same
 * artifact bytes the validation manifest already produced and
 * independently verified -- never a fresh render.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class ExportTransactionIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-export-transaction-integration";

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
    void exportingAnApprovedRevisionProducesAReceiptNamingTheExactValidatedArtifacts() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-export-happy");
        long workspaceId = ensureWorkspace("subject-export-happy").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Spring Budget Planning minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Spring Budget Planning"},
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

        JsonNode manifest = readJson(mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/validate")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "validate-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        long docxArtifactId = manifest.get("docxArtifactId").asLong();
        long pdfArtifactId = manifest.get("pdfArtifactId").asLong();
        String docxSha256 = manifest.get("docxSha256").asText();
        String pdfSha256 = manifest.get("pdfSha256").asText();

        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export-approval")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"BOTH\"}"))
                .andExpect(status().isCreated());

        JsonNode receipt = readJson(mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(receipt.get("docxArtifactId").asLong()).isEqualTo(docxArtifactId);
        assertThat(receipt.get("pdfArtifactId").asLong()).isEqualTo(pdfArtifactId);
        assertThat(receipt.get("docxSha256").asText()).isEqualTo(docxSha256);
        assertThat(receipt.get("pdfSha256").asText()).isEqualTo(pdfSha256);
        assertThat(receipt.get("isCompletePair").asBoolean()).isTrue();
        assertThat(receipt.get("format").asText()).isEqualTo("BOTH");

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/uploads/" + docxArtifactId + "/download").cookie(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/uploads/" + pdfArtifactId + "/download").cookie(session))
                .andExpect(status().isOk());

        JsonNode latest = readJson(mockMvc.perform(get(documentsPath(workspaceId) + "/" + documentId + "/export-receipt")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(latest.get("id").asLong()).isEqualTo(receipt.get("id").asLong());

        // The export is on the record beside its receipt: who, which revision, which format, and nothing the document said.
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                java.sql.PreparedStatement statement = connection.prepareStatement("""
                        SELECT a.actor_user_id = r.actor_user_id, a.resource_type, a.details::text, r.revision_id,
                               (a.details ->> 'revisionId')::bigint
                        FROM audit_event a JOIN export_receipt r ON r.id = (a.details ->> 'exportReceiptId')::bigint
                        WHERE a.workspace_id = ? AND a.action = 'DOCUMENT_EXPORTED' AND a.resource_id = ?
                        """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, documentId);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("recorded in the exporter's own name").isTrue();
                assertThat(rs.getString(2)).isEqualTo("document");
                // Validating wrote the revision that was exported, so the receipt, not the draft this test created, names it.
                assertThat(rs.getLong(4)).as("the receipt's revision").isEqualTo(receipt.get("revisionId").asLong());
                assertThat(rs.getLong(5)).as("the audit row's revision").isEqualTo(receipt.get("revisionId").asLong());
                assertThat(rs.getString(3))
                        .contains("\"exportReceiptId\": " + receipt.get("id").asLong())
                        .contains("\"format\": \"BOTH\"")
                        .doesNotContain("Spring Budget");
                assertThat(rs.next()).as("one export, one audit row").isFalse();
            }
        }

        // A second click, or a retry after a lost response: the same approval and the same receipt, not new ones, and
        // still one entry in the audit record.
        JsonNode approvedAgain = readJson(mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export-approval")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"BOTH\"}"))
                .andExpect(status().isCreated())
                .andReturn());
        JsonNode exportedAgain = readJson(mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(approvedAgain.get("id").asLong()).isEqualTo(receipt.get("exportApprovalId").asLong());
        assertThat(exportedAgain.get("id").asLong()).isEqualTo(receipt.get("id").asLong());
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                java.sql.PreparedStatement statement = connection.prepareStatement("""
                        SELECT (SELECT count(*) FROM export_approval WHERE document_id = ?),
                               (SELECT count(*) FROM export_receipt WHERE document_id = ?),
                               (SELECT count(*) FROM audit_event WHERE action = 'DOCUMENT_EXPORTED' AND resource_id = ?)
                        """)) {
            statement.setLong(1, documentId);
            statement.setLong(2, documentId);
            statement.setLong(3, documentId);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).as("approvals").isEqualTo(1);
                assertThat(rs.getLong(2)).as("receipts").isEqualTo(1);
                assertThat(rs.getLong(3)).as("audit rows").isEqualTo(1);
            }
        }
        // A different choice is a different decision, and is recorded as one.
        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export-approval")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"DOCX\"}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id")
                        .value(org.hamcrest.Matchers.not((int) receipt.get("exportApprovalId").asLong())));

        // And what was approved is what is exported: the Word file alone, though a PDF of this revision exists.
        JsonNode wordOnly = readJson(mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(wordOnly.get("format").asText()).isEqualTo("DOCX");
        assertThat(wordOnly.get("docxArtifactId").asLong()).isEqualTo(docxArtifactId);
        assertThat(wordOnly.get("pdfArtifactId").isNull()).isTrue();
        assertThat(wordOnly.get("id").asLong()).isNotEqualTo(receipt.get("id").asLong());
    }

    @Test
    void exportingWithoutAnyApprovalIsRefused() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-export-unapproved");
        long workspaceId = ensureWorkspace("subject-export-unapproved").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Never approved minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Never approved minutes"},
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

        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/export")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isNotFound());
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
