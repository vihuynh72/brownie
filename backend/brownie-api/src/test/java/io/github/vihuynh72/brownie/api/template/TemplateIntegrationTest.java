package io.github.vihuynh72.brownie.api.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.rule.EmptyValueResolution;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.rule.RuleScope;
import io.github.vihuynh72.brownie.core.rule.RuleVocabulary;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtContentRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtRun;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the whole upload-then-extract-then-draft-then-bind-then-activate
 * flow end to end, through real HTTP dispatch, real
 * Postgres/Azurite/ClamAV, and a real authenticated session -- the same
 * infrastructure pattern {@code ExtractionIntegrationTest} already
 * established for the extraction workflow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class TemplateIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-template-integration";

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

    // A plain, local instance, not @Autowired -- see ExtractionIntegrationTest's
    // own identical comment: this Boot line autoconfigures a Jackson 3
    // ObjectMapper bean, not this com.fasterxml.jackson one.
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
    private RuleRepository ruleRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Test
    void activationIsRefusedWhenTwoRealPersistedRulesDirectlyConflict() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-rule-conflict");
        long workspaceId = ensureWorkspace("subject-rule-conflict").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "minutes.docx");
        extract(session, workspaceId, artifactId);
        long templateId = createDraft(session, workspaceId, artifactId);
        String bindingsBody = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"meeting.title\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"OPTIONAL\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"meeting.title\"}"
                + "}]}";
        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(bindingsBody))
                .andExpect(status().isOk());

        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-rule-conflict").orElseThrow().id();
        long draftVersionId = templateRepository.findDraftVersion(workspaceId, userId, templateId).orElseThrow().id();
        ruleRepository.propose(
                workspaceId, userId, templateId, draftVersionId, new RuleScope.WholeTemplate(),
                new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.OMIT), RuleVocabulary.SCHEMA_VERSION, null);
        ruleRepository.propose(
                workspaceId, userId, templateId, draftVersionId, new RuleScope.WholeTemplate(),
                new RulePayload.MissingValueBehavior("meeting.title", EmptyValueResolution.BLANK), RuleVocabulary.SCHEMA_VERSION, null);

        JsonNode problem = readJson(mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isConflict())
                .andReturn());
        assertThat(problem.get("conflicts")).hasSize(1);
        assertThat(problem.get("conflicts").get(0).get("reason").asText()).isEqualTo("DIRECT_CONTRADICTION");
        var template = templateRepository.find(workspaceId, userId, templateId).orElseThrow();
        assertThat(template.status().name()).isEqualTo("DRAFT");
        assertThat(template.currentActiveVersionId()).isNull();
    }

    @Test
    void activationRejectsARuleThatBecameStaleAfterTheDraftBindingsChanged() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-stale-rule");
        long workspaceId = ensureWorkspace("subject-stale-rule").id();
        long artifactId = uploadAndFinalize(
                session, workspaceId, docxWithContentControl("meeting.title", "meeting.location"), "minutes.docx");
        extract(session, workspaceId, artifactId);
        long templateId = createDraft(session, workspaceId, artifactId);

        String titleBindings = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"meeting.title\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"OPTIONAL\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"meeting.title\"}"
                + "}]}";
        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(titleBindings))
                .andExpect(status().isOk());

        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-stale-rule").orElseThrow().id();
        long draftVersionId = templateRepository.findDraftVersion(workspaceId, userId, templateId).orElseThrow().id();
        ruleRepository.propose(
                workspaceId,
                userId,
                templateId,
                draftVersionId,
                new RuleScope.SingleField("meeting.title"),
                new RulePayload.MaxTextLength("meeting.title", 100),
                RuleVocabulary.SCHEMA_VERSION,
                null);

        String locationBindings = "{"
                + "\"expectedVersionNumber\":2,"
                + "\"fields\":[{"
                + "\"fieldId\":\"meeting.location\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"OPTIONAL\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"meeting.location\"}"
                + "}]}";
        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(locationBindings))
                .andExpect(status().isOk());

        JsonNode problem = readJson(mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn());
        assertThat(problem.get("code").asText()).isEqualTo("RULE_VALIDATION_FAILED");
        var template = templateRepository.find(workspaceId, userId, templateId).orElseThrow();
        assertThat(template.status().name()).isEqualTo("DRAFT");
        assertThat(template.currentActiveVersionId()).isNull();
    }

    @Test
    void anActivatedTemplateRejectsLaterBindingChangesAndKeepsItsVersionIntact() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-active-immutable");
        long workspaceId = ensureWorkspace("subject-active-immutable").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "minutes.docx");
        extract(session, workspaceId, artifactId);
        long templateId = createDraft(session, workspaceId, artifactId);
        String titleBindings = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"meeting.title\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"meeting.title\"}"
                + "}]}";
        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(titleBindings))
                .andExpect(status().isOk());
        mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isCreated());

        String changedBindings = "{"
                + "\"expectedVersionNumber\":2,"
                + "\"fields\":[{"
                + "\"fieldId\":\"meeting.renamed\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"meeting.title\"}"
                + "}]}";
        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(changedBindings))
                .andExpect(status().isConflict());

        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-active-immutable").orElseThrow().id();
        var template = templateRepository.find(workspaceId, userId, templateId).orElseThrow();
        var activated = templateRepository.findVersion(workspaceId, userId, templateId, template.currentActiveVersionId()).orElseThrow();
        assertThat(activated.status().name()).isEqualTo("ACTIVATED");
        assertThat(activated.versionNumber()).isEqualTo(2);
        assertThat(activated.fieldDefinitions()).extracting(field -> field.fieldId()).containsExactly("meeting.title");
    }

    @Test
    void theWholeDraftBindAndActivateFlowSucceedsAgainstARealExtractedDocx() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-happy-path");
        long workspaceId = ensureWorkspace("subject-happy-path").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "minutes.docx");
        extract(session, workspaceId, artifactId);

        JsonNode created = readJson(mockMvc.perform(post(templatesPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Club Minutes\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        long templateId = created.get("template").get("id").asLong();
        assertThat(created.get("template").get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.get("draftVersion").get("versionNumber").asInt()).isEqualTo(1);

        String bindingsBody = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"meeting.title\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"meeting.title\"}"
                + "}]}";
        JsonNode replaced = readJson(mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(bindingsBody))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(replaced.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(replaced.get("fields")).hasSize(1);

        JsonNode activated = readJson(mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(activated.get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(activated.get("activatedAt").isNull()).isFalse();
    }

    @Test
    void creatingADraftBeforeExtractionHasRunReturnsUnprocessable() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-not-extracted");
        long workspaceId = ensureWorkspace("subject-not-extracted").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "minutes.docx");

        mockMvc.perform(post(templatesPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Club Minutes\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void replacingBindingsWithATagAbsentFromTheExtractionReturnsUnprocessableWithTheFailingField() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-unsupported-binding");
        long workspaceId = ensureWorkspace("subject-unsupported-binding").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "minutes.docx");
        extract(session, workspaceId, artifactId);
        long templateId = createDraft(session, workspaceId, artifactId);

        String bindingsBody = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"no.such.field\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"no.such.tag\"}"
                + "}]}";
        JsonNode problem = readJson(mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(bindingsBody))
                .andExpect(status().isUnprocessableEntity())
                .andReturn());
        assertThat(problem.get("fields").get(0).get("field").asText()).isEqualTo("no.such.field");
        assertThat(problem.get("fields").get(0).get("message").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void replacingBindingsWithAStaleExpectedVersionNumberReturnsConflict() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-stale-http");
        long workspaceId = ensureWorkspace("subject-stale-http").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "minutes.docx");
        extract(session, workspaceId, artifactId);
        long templateId = createDraft(session, workspaceId, artifactId);

        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":99,\"fields\":[]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void activatingAnEmptyDraftReturnsConflict() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-empty-activate");
        long workspaceId = ensureWorkspace("subject-empty-activate").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "minutes.docx");
        extract(session, workspaceId, artifactId);
        long templateId = createDraft(session, workspaceId, artifactId);

        mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void creatingATemplateWithoutAuthenticationIsRejected() throws Exception {
        var identity = userIdentityRepository.recordLogin(ISSUER, "subject-unauth-owner", null, null);
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(identity.id()).id();
        // A valid CSRF token is supplied, so that gate passes and
        // authentication is the next real gate reached -- 401, not the 403
        // a POST with no CSRF token at all would get instead (already
        // covered by the upload routes' own tests).
        mockMvc.perform(post(templatesPath(workspaceId))
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Club Minutes\",\"sourceArtifactId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    private static String templatesPath(long workspaceId) {
        return "/api/v1/workspaces/" + workspaceId + "/templates";
    }

    private void extract(Cookie session, long workspaceId, long artifactId) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/artifacts/" + artifactId + "/extraction")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private long createDraft(Cookie session, long workspaceId, long artifactId) throws Exception {
        JsonNode created = readJson(mockMvc.perform(post(templatesPath(workspaceId))
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Club Minutes\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        return created.get("template").get("id").asLong();
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

    /** A minimal real DOCX with one labeled paragraph and one inline content control for each tag. */
    private static byte[] docxWithContentControl(String... tags) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String tag : tags) {
                XWPFParagraph paragraph = doc.createParagraph();
                paragraph.createRun().setText("Field: ");
                CTP ctp = paragraph.getCTP();
                CTSdtRun sdt = ctp.addNewSdt();
                CTSdtPr sdtPr = sdt.addNewSdtPr();
                sdtPr.addNewTag().setVal(tag);
                sdtPr.addNewAlias().setVal(tag);
                CTSdtContentRun content = sdt.addNewSdtContent();
                CTR run = content.addNewR();
                run.addNewT().setStringValue("[value]");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private JsonNode readJson(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** Same real-session-through-the-real-repository pattern as {@code ExtractionIntegrationTest}. */
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
