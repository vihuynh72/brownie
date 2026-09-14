package io.github.vihuynh72.brownie.api.validation;

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
 * Proves the validation pipeline end to end through real HTTP dispatch and
 * a real Postgres/Azurite/ClamAV stack (no renderer needed yet -- this
 * task does not render a PDF; see {@code ValidationService}'s own
 * javadoc): upload a built-in blank fixture, extract it, bind and activate
 * a template from it, create a document instance, then validate that exact
 * revision and inspect the resulting manifest and the field-level {@code
 * validation} dimension it wrote back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class ValidationIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-validation-integration";

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
    void validatingACompleteRevisionProducesNoBlockingFindingsAndMarksEveryFieldPassed() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-validate-happy");
        long workspaceId = ensureWorkspace("subject-validate-happy").id();
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

        assertThat(manifest.get("documentId").asLong()).isEqualTo(documentId);
        assertThat(manifest.get("templateVersionId").asLong()).isEqualTo(templateVersionId);
        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean()).isFalse();
        // Template activation already proved a real qualified baseline (Phase 12's own activation gate), so this
        // run renders a real PDF and compares against it -- never null once a baseline actually exists.
        assertThat(manifest.get("pdfArtifactId").isNull()).isFalse();
        for (JsonNode finding : manifest.get("findings")) {
            assertThat(finding.get("severity").asText()).as(finding.toString()).isNotEqualTo("BLOCKING");
        }
        long validatedRevisionId = manifest.get("revisionId").asLong();
        assertThat(validatedRevisionId).isNotEqualTo(revisionId);

        mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/uploads/" + manifest.get("pdfArtifactId").asLong() + "/download")
                        .cookie(session))
                .andExpect(status().isOk());

        JsonNode latest = readJson(mockMvc.perform(
                        get(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + validatedRevisionId + "/validation")
                                .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(latest.get("id").asLong()).isEqualTo(manifest.get("id").asLong());

        JsonNode revision = readJson(mockMvc.perform(
                        get(documentsPath(workspaceId) + "/" + documentId + "/revisions/" + validatedRevisionId)
                                .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        for (java.util.Map.Entry<String, JsonNode> entry : revision.get("fields").properties()) {
            JsonNode fieldValue = entry.getValue();
            if (fieldValue.hasNonNull("fieldState")) {
                assertThat(fieldValue.get("fieldState").get("validation").asText()).as(entry.getKey()).isEqualTo("PASSED");
            }
            if (fieldValue.hasNonNull("itemFieldStates")) {
                for (JsonNode itemState : fieldValue.get("itemFieldStates")) {
                    assertThat(itemState.get("validation").asText()).as(entry.getKey()).isEqualTo("PASSED");
                }
            }
        }
    }

    @Test
    void aMissingRequiredFieldProducesABlockingFindingAndMarksThatFieldBlocked() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-validate-missing-required");
        long workspaceId = ensureWorkspace("subject-validate-missing-required").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        // Deliberately omits the required meeting.date field.
        String createBody = """
                {
                  "title": "Missing date minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Missing date minutes"}
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

        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean()).isTrue();
        boolean sawMissingDate = false;
        for (JsonNode finding : manifest.get("findings")) {
            if (finding.get("code").asText().equals("MISSING_REQUIRED_FIELD") && finding.get("fieldId").asText().equals("meeting.date")) {
                sawMissingDate = true;
                assertThat(finding.get("severity").asText()).isEqualTo("BLOCKING");
            }
        }
        assertThat(sawMissingDate).as(manifest.toString()).isTrue();
    }

    @Test
    void validatingWithAStaleExpectedRevisionIsRejected() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-validate-stale");
        long workspaceId = ensureWorkspace("subject-validate-stale").id();
        BuiltInMinutesTemplate template = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        long templateVersionId = activateBuiltInTemplate(session, workspaceId, template);
        long templateId = lastCreatedTemplateId;

        String createBody = """
                {
                  "title": "Stale revision minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "Stale revision minutes"},
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
        long firstRevisionId = created.get("currentRevision").get("id").asLong();

        // Validating once always appends a new revision (it records the per-field validation dimension),
        // so the original revision id is now genuinely stale -- not a guessed, possibly-nonexistent id.
        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/validate")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "validate-first-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + firstRevisionId + "}"))
                .andExpect(status().isCreated());

        // DocumentRevisionConflictException maps to 412 Precondition Failed (STALE_REVISION), the same
        // status every other optimistic-concurrency document mutation in this codebase already uses.
        mockMvc.perform(post(documentsPath(workspaceId) + "/" + documentId + "/validate")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", "validate-stale-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + firstRevisionId + "}"))
                .andExpect(status().isPreconditionFailed());
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
