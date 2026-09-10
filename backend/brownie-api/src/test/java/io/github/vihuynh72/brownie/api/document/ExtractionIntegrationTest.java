package io.github.vihuynh72.brownie.api.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
import org.testcontainers.utility.DockerImageName;
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
 * Proves the whole upload-then-extract-then-read flow end to end, through
 * real HTTP dispatch, real Postgres/Azurite/ClamAV, and a real
 * authenticated session -- for both a real DOCX and a real PDF, so the
 * controller's own media-type dispatch ({@link ExtractionController}) is
 * exercised for real rather than only at the service layer, the same
 * infrastructure pattern {@code ArtifactUploadIntegrationTest} already
 * established.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class ExtractionIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

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
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(DockerImageName.parse("clamav/clamav-debian:1.4"))
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

    // A plain, local instance, not @Autowired -- see ArtifactUploadIntegrationTest's
    // own identical comment: this Boot line autoconfigures a Jackson 3
    // ObjectMapper bean, not this com.fasterxml.jackson one, so there is
    // nothing of this exact type to inject. Fine for ad hoc test-side JSON
    // reading, which is all this needs.
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
    void aRealDocxIsUploadedExtractedAndReadBackAsComplete() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-extraction-docx");
        long workspaceId = ensureWorkspace("https://issuer-extraction-integration", "subject-extraction-docx").id();
        long artifactId = uploadAndFinalize(session, workspaceId, minimalDocxBytes(), "minutes.docx");

        JsonNode extractResponse = readJson(mockMvc.perform(post(extractionPath(workspaceId, artifactId)).cookie(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(extractResponse.get("format").asText()).isEqualTo("DOCX");
        assertThat(extractResponse.get("status").asText()).isEqualTo("COMPLETE");

        JsonNode latestResponse =
                readJson(mockMvc.perform(get(extractionPath(workspaceId, artifactId)).cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(latestResponse.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(latestResponse.get("id").asLong()).isEqualTo(extractResponse.get("id").asLong());
    }

    @Test
    void aRealPdfIsUploadedExtractedAndReadBackAsCompleteWithPageDetail() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-extraction-pdf");
        long workspaceId = ensureWorkspace("https://issuer-extraction-integration", "subject-extraction-pdf").id();
        long artifactId = uploadAndFinalize(session, workspaceId, minimalPdfBytes(), "minutes.pdf");

        JsonNode extractResponse = readJson(mockMvc.perform(post(extractionPath(workspaceId, artifactId)).cookie(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(extractResponse.get("format").asText()).isEqualTo("PDF");
        assertThat(extractResponse.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(extractResponse.get("pages")).hasSize(1);
        assertThat(extractResponse.get("pages").get(0).get("pageNumber").asInt()).isEqualTo(1);
        assertThat(extractResponse.get("pages").get(0).get("hasExtractableText").asBoolean()).isTrue();
    }

    @Test
    void extractionOnAnArtifactThatIsStillUploadingReturnsConflict() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-extraction-not-ready");
        long workspaceId = ensureWorkspace("https://issuer-extraction-integration", "subject-extraction-not-ready").id();

        JsonNode allocateResponse = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/uploads")
                        .cookie(session)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        long artifactId = allocateResponse.get("id").asLong();

        mockMvc.perform(post(extractionPath(workspaceId, artifactId)).cookie(session).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void extractionOnANonexistentArtifactReturnsNotFound() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-extraction-missing");
        long workspaceId = ensureWorkspace("https://issuer-extraction-integration", "subject-extraction-missing").id();

        mockMvc.perform(post(extractionPath(workspaceId, 999_999_999L)).cookie(session).with(csrf())).andExpect(status().isNotFound());
    }

    private static String extractionPath(long workspaceId, long artifactId) {
        return "/api/v1/workspaces/" + workspaceId + "/artifacts/" + artifactId + "/extraction";
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

    private static byte[] minimalDocxBytes() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Meeting called to order.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] minimalPdfBytes() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Meeting called to order.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private JsonNode readJson(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** Same real-session-through-the-real-repository pattern as {@code ArtifactUploadIntegrationTest}. */
    private Cookie loginAndGetSessionCookie(String subject) {
        String issuer = "https://issuer-extraction-integration";
        userIdentityRepository.recordLogin(issuer, subject, null, null);
        Workspace workspace = ensureWorkspace(issuer, subject);
        assertThat(workspace).isNotNull();

        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, issuer)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));

        Session session = createAuthenticatedSession(sessionRepository, context);
        return new Cookie(
                "SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String issuer, String subject) {
        var identity = userIdentityRepository.findByIssuerAndSubject(issuer, subject).orElseThrow();
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
