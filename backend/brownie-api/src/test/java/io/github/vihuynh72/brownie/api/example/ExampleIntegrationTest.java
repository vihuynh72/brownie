package io.github.vihuynh72.brownie.api.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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
 * Proves the whole attach-and-list example flow end to end, through real
 * HTTP dispatch, real Postgres/Azurite/ClamAV, and a real authenticated
 * session -- the same infrastructure pattern {@code TemplateIntegrationTest}
 * already established for the template draft/bind/activate flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class ExampleIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-example-integration";

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

    // A plain, local instance, not @Autowired -- see TemplateIntegrationTest's
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

    @Test
    void attachingAnExampleThatMatchesTheDraftsBoundTagReportsAligned() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-aligned");
        long workspaceId = ensureWorkspace("subject-aligned").id();
        long templateArtifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "blank.docx");
        extract(session, workspaceId, templateArtifactId);
        long templateId = createDraft(session, workspaceId, templateArtifactId);
        bindTitleField(session, workspaceId, templateId);

        long exampleArtifactId =
                uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "example.docx");
        extract(session, workspaceId, exampleArtifactId);

        JsonNode attached = readJson(mockMvc.perform(post(examplesPath(workspaceId, templateId))
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + exampleArtifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(attached.get("alignmentStatus").asText()).isEqualTo("ALIGNED");
        assertThat(attached.get("sourceArtifactId").asLong()).isEqualTo(exampleArtifactId);

        JsonNode listed = readJson(mockMvc.perform(get(examplesPath(workspaceId, templateId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("id").asLong()).isEqualTo(attached.get("id").asLong());
    }

    @Test
    void attachingAnExampleMissingTheBoundTagReportsMismatchedFamilyRatherThanFailing() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-mismatched");
        long workspaceId = ensureWorkspace("subject-mismatched").id();
        long templateArtifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "blank.docx");
        extract(session, workspaceId, templateArtifactId);
        long templateId = createDraft(session, workspaceId, templateArtifactId);
        bindTitleField(session, workspaceId, templateId);

        long exampleArtifactId =
                uploadAndFinalize(session, workspaceId, docxWithContentControl("some.unrelated.field"), "example.docx");
        extract(session, workspaceId, exampleArtifactId);

        JsonNode attached = readJson(mockMvc.perform(post(examplesPath(workspaceId, templateId))
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + exampleArtifactId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(attached.get("alignmentStatus").asText()).isEqualTo("MISMATCHED_FAMILY");
    }

    @Test
    void attachingAnExampleBeforeAnyFieldIsBoundReturnsUnprocessable() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-no-fields");
        long workspaceId = ensureWorkspace("subject-no-fields").id();
        long templateArtifactId = uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "blank.docx");
        extract(session, workspaceId, templateArtifactId);
        long templateId = createDraft(session, workspaceId, templateArtifactId);

        long exampleArtifactId =
                uploadAndFinalize(session, workspaceId, docxWithContentControl("meeting.title"), "example.docx");
        extract(session, workspaceId, exampleArtifactId);

        JsonNode problem = readJson(mockMvc.perform(post(examplesPath(workspaceId, templateId))
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + exampleArtifactId + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn());
        assertThat(problem.get("code").asText()).isEqualTo("NO_COMPARABLE_FIELD_BINDINGS");
    }

    private void bindTitleField(Cookie session, long workspaceId, long templateId) throws Exception {
        String bindingsBody = "{"
                + "\"expectedVersionNumber\":1,"
                + "\"fields\":[{"
                + "\"fieldId\":\"meeting.title\",\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"requiredness\":\"REQUIRED\","
                + "\"binding\":{\"kind\":\"CONTENT_CONTROL_TAG\",\"tag\":\"meeting.title\"}"
                + "}]}";
        mockMvc.perform(put("/api/v1/workspaces/" + workspaceId + "/templates/" + templateId + "/draft/bindings")
                        .cookie(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content(bindingsBody))
                .andExpect(status().isOk());
    }

    private static String examplesPath(long workspaceId, long templateId) {
        return "/api/v1/workspaces/" + workspaceId + "/templates/" + templateId + "/examples";
    }

    private void extract(Cookie session, long workspaceId, long artifactId) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/artifacts/" + artifactId + "/extraction")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    private long createDraft(Cookie session, long workspaceId, long artifactId) throws Exception {
        JsonNode created = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/templates")
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

    /** A minimal real DOCX with one inline content control per tag -- mirrors {@code TemplateIntegrationTest}'s own identical fixture helper. */
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

    /** Same real-session-through-the-real-repository pattern as {@code TemplateIntegrationTest}. */
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
