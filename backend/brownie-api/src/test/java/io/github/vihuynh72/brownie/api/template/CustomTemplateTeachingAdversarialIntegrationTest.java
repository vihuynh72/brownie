package io.github.vihuynh72.brownie.api.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.example.TemplateExample;
import io.github.vihuynh72.brownie.core.example.TemplateExampleService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.rule.RuleRevision;
import io.github.vihuynh72.brownie.core.rule.RuleService;
import io.github.vihuynh72.brownie.core.template.TemplateService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * This phase's own closing adversarial sweep, proven end to end against real
 * Postgres/Azurite/ClamAV and the real Docker-isolated LibreOffice renderer,
 * on a held-out custom template none of this phase's earlier per-task tests
 * ever used: the whole teaching pipeline (preflight, candidate proposal,
 * manual mapping recovery for an ambiguous tag, example alignment, rule
 * proposal with a genuine contradiction, accept/reject decisions, and a
 * real baseline-gated activation) composed exactly the way a real caller
 * would, using only already-built API surface. {@code proposeRulesFromExamples}
 * and rule accept/reject decisions have no REST endpoint by this phase's
 * own deliberate choice, so this test calls those two steps directly
 * through their already real-HTTP-independently-tested services, the same
 * precedent {@code TemplateIntegrationTest}'s own rule-conflict tests
 * already set by calling {@code RuleRepository.propose} directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class CustomTemplateTeachingAdversarialIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-custom-teaching-adversarial";

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
    private TemplateExampleService templateExampleService;

    @Autowired
    private RuleService ruleService;

    @Autowired
    private TemplateService templateService;

    @Test
    void aHeldOutCustomTemplateWithContradictoryExamplesActivatesEndToEndThroughOnlyAlreadyBuiltApiSurface() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-held-out");
        long workspaceId = ensureWorkspace("subject-held-out").id();
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-held-out").orElseThrow().id();

        long blankArtifactId = uploadAndFinalize(
                session, workspaceId, docxWithScalarTags("club.event.name", "club.event.date"), "held-out-blank.docx");
        extract(session, workspaceId, blankArtifactId);
        long templateId = createDraft(session, workspaceId, blankArtifactId);

        JsonNode candidates = readJson(mockMvc.perform(get(templatesPath(workspaceId) + "/" + templateId + "/draft/candidate-bindings")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(candidates.get("candidates")).hasSize(2);
        String bindingsBody = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"club.event.name\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"club.event.name\"}"
                + "},{"
                + "\"fieldId\":\"club.event.date\",\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"club.event.date\"}"
                + "}]}";
        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(bindingsBody))
                .andExpect(status().isOk());

        // Two examples agree on an ISO date; a third, otherwise-aligned example disagrees --
        // a genuine, real contradiction the proposer must name, not hide, while still proposing the majority style.
        long aligned1 = attachRealExample(session, workspaceId, templateId, "2026-09-10");
        long aligned2 = attachRealExample(session, workspaceId, templateId, "2026-10-01");
        long contradicting = attachRealExample(session, workspaceId, templateId, "10/1/2026");

        // Every example also shares the identical "Annual Meeting" text for club.event.name, so the
        // proposer's own honest, mechanical MaxTextLength rule is expected here too -- proven separately
        // and thoroughly in ExampleRuleProposerTest; this test only needs the DateDisplayFormat one.
        List<RuleRevision> proposed = templateExampleService.proposeRulesFromExamples(workspaceId, userId, templateId);
        assertThat(proposed).hasSize(2);
        RuleRevision dateRule = proposed.stream().filter(r -> r.payload() instanceof RulePayload.DateDisplayFormat).findFirst().orElseThrow();
        assertThat(dateRule.payload()).isEqualTo(new RulePayload.DateDisplayFormat("club.event.date", io.github.vihuynh72.brownie.core.rule.DateFormatStyle.ISO));
        var evidence = ruleService.findProposalEvidence(workspaceId, userId, dateRule.id()).orElseThrow();
        assertThat(evidence.supportingExampleIds()).containsExactlyInAnyOrder(aligned1, aligned2);
        assertThat(evidence.contradictingExampleIds()).containsExactly(contradicting);

        ruleService.acceptRule(workspaceId, userId, templateId, dateRule.id());

        JsonNode activated = readJson(mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(activated.get("status").asText()).isEqualTo("ACTIVATED");

        var baseline = templateService.findBaselineRender(workspaceId, userId, activated.get("id").asLong());
        assertThat(baseline).isPresent();
        assertThat(baseline.orElseThrow().passed()).isTrue();
    }

    /**
     * A real, adversarially-discovered boundary, proven and locked in here
     * rather than quietly worked around: manual {@code StructuralNode}
     * mapping genuinely recovers a person all the way through binding
     * (this exact scenario -- an ambiguous, never-proposed tag) and past
     * binding validation, but activation's own new baseline render then
     * correctly refuses, because {@code PoiTemplateFiller} only ever knows
     * how to fill a {@code ContentControlTag}-bound field -- a real,
     * pre-existing gap this task's own baseline gate surfaces at
     * activation time instead of letting the template activate and fail
     * later on a real user's first document. This is the honest, current
     * behavior, not the desired end state; see this task's own journal
     * entry for why extending the filler itself was judged out of this
     * task's own scope.
     */
    @Test
    void aTagRepeatedAtTwoLocationsRecoversThroughManualMappingButActivationHonestlyRefusesAStructuralNodeBoundField() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-repeated-label");
        long workspaceId = ensureWorkspace("subject-repeated-label").id();
        long artifactId = uploadAndFinalize(
                session, workspaceId, docxWithScalarTags("club.event.name", "club.event.name"), "repeated-label.docx");
        extract(session, workspaceId, artifactId);
        long templateId = createDraft(session, workspaceId, artifactId);

        JsonNode candidates = readJson(mockMvc.perform(get(templatesPath(workspaceId) + "/" + templateId + "/draft/candidate-bindings")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(candidates.get("candidates")).isEmpty();
        assertThat(candidates.get("ambiguousContentControlTags")).isNotEmpty();
        assertThat(candidates.get("ambiguousContentControlTags").get(0).asText()).isEqualTo("club.event.name");

        JsonNode structure = readJson(mockMvc.perform(get(templatesPath(workspaceId) + "/" + templateId + "/draft/structure")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        String firstNodeId = structure.get("parts").get(0).get("root").get("children").get(0).get("nodeId").asText();

        String manualBindingsBody = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"club.event.name\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"OPTIONAL\","
                + "\"binding\":{\"kind\":\"STRUCTURAL_NODE\",\"part\":\"MAIN_DOCUMENT\",\"nodeId\":\"" + firstNodeId + "\"}"
                + "}]}";
        mockMvc.perform(put(templatesPath(workspaceId) + "/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(manualBindingsBody))
                .andExpect(status().isOk());

        JsonNode problem = readJson(mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/versions")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn());
        assertThat(problem.get("code").asText()).isEqualTo("TEMPLATE_FILL_UNREADABLE_TEMPLATE");
        assertThat(problem.get("detail").asText()).contains("club.event.name").contains("not bound by a stable content-control tag");
    }

    private long attachRealExample(Cookie session, long workspaceId, long templateId, String dateText) throws Exception {
        long artifactId = uploadAndFinalize(
                session, workspaceId, docxWithTaggedText("club.event.name", "Annual Meeting", "club.event.date", dateText),
                "example-" + java.util.UUID.randomUUID() + ".docx");
        extract(session, workspaceId, artifactId);
        JsonNode attached = readJson(mockMvc.perform(post(templatesPath(workspaceId) + "/" + templateId + "/examples")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(attached.get("alignmentStatus").asText()).isEqualTo("ALIGNED");
        return attached.get("id").asLong();
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
                        .content("{\"displayName\":\"Held-Out Club Template\",\"sourceArtifactId\":" + artifactId + "}"))
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

    /** One plain content control per tag, no text -- mirrors {@code TemplateIntegrationTest}'s own identical fixture helper, duplicated tags allowed on purpose for the repeated-label case. */
    private static byte[] docxWithScalarTags(String... tags) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String tag : tags) {
                addContentControlParagraph(doc.createParagraph(), tag, "[value]");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** {@code tagsAndText} alternates tag, text -- a content control per pair, its own literal text, for building a real example DOCX. */
    private static byte[] docxWithTaggedText(String... tagsAndText) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (int index = 0; index < tagsAndText.length; index += 2) {
                addContentControlParagraph(doc.createParagraph(), tagsAndText[index], tagsAndText[index + 1]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void addContentControlParagraph(XWPFParagraph paragraph, String tag, String text) {
        paragraph.createRun().setText("Field: ");
        CTP ctp = paragraph.getCTP();
        CTSdtRun sdt = ctp.addNewSdt();
        CTSdtPr sdtPr = sdt.addNewSdtPr();
        sdtPr.addNewTag().setVal(tag);
        sdtPr.addNewAlias().setVal(tag);
        CTSdtContentRun content = sdt.addNewSdtContent();
        CTR run = content.addNewR();
        run.addNewT().setStringValue(text);
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
