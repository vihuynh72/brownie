package io.github.vihuynh72.brownie.api.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
@DockerTest
class ExtractionIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", DB::azuriteConnectionString);
        registry.add("brownie.security.clamav.host", SharedContainers::clamAvHost);
        registry.add("brownie.security.clamav.port", SharedContainers::clamAvPort);
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

    /** A table inside a table and a page number no longer stop the read; the response names them as kept as they are. */
    @Test
    void aDocxThatKeepsSomethingAsItIsIsCompleteAndSaysWhat() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-extraction-kept");
        long workspaceId = ensureWorkspace("https://issuer-extraction-integration", "subject-extraction-kept").id();
        long artifactId = uploadAndFinalize(session, workspaceId, docxKeepingATableInATableAndAPageNumber(), "kept.docx");

        JsonNode extractResponse = readJson(mockMvc.perform(post(extractionPath(workspaceId, artifactId)).cookie(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(extractResponse.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(extractResponse.get("parserVersion").asText()).isEqualTo("brownie-docx-graph-v3+poi-5.5.1");
        assertThat(extractResponse.get("unsupportedFeatures")).isEmpty();
        List<String> kept = new java.util.ArrayList<>();
        extractResponse.get("keptAsIsFeatures").forEach(finding -> kept.add(finding.get("feature").asText()));
        assertThat(kept).containsExactlyInAnyOrder("NESTED_TABLE", "DYNAMIC_FIELD");

        JsonNode latestResponse =
                readJson(mockMvc.perform(get(extractionPath(workspaceId, artifactId)).cookie(session)).andExpect(status().isOk()).andReturn());
        assertThat(latestResponse.get("keptAsIsFeatures")).isEqualTo(extractResponse.get("keptAsIsFeatures"));
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
    void aRealPlainTextFileIsUploadedExtractedAndReadBackAsCompleteWithNormalizedLength() throws Exception {
        Cookie session = loginAndGetSessionCookie("subject-extraction-text");
        long workspaceId = ensureWorkspace("https://issuer-extraction-integration", "subject-extraction-text").id();
        String rawText = "Meeting called to order.\r\nAttendees: Jordan Lee.";
        long artifactId = uploadAndFinalize(session, workspaceId, rawText.getBytes(StandardCharsets.UTF_8), "notes.txt");

        JsonNode extractResponse = readJson(mockMvc.perform(post(extractionPath(workspaceId, artifactId)).cookie(session).with(csrf()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(extractResponse.get("format").asText()).isEqualTo("PLAIN_TEXT");
        assertThat(extractResponse.get("status").asText()).isEqualTo("COMPLETE");
        // Computed from the raw text itself (\r\n -> \n loses exactly one
        // character) rather than a hand-counted literal -- a hand count of
        // this exact string was tried first and was wrong by 2, caught by
        // the test itself failing, not by re-reading the count more
        // carefully.
        int expectedNormalizedLength = rawText.replace("\r\n", "\n").length();
        assertThat(extractResponse.get("normalizedTextLength").asInt()).isEqualTo(expectedNormalizedLength);
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

    private static byte[] docxKeepingATableInATableAndAPageNumber() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            var outer = doc.createTable(1, 1);
            var cell = outer.getRow(0).getCell(0);
            try (var cursor = cell.getParagraphs().get(0).getCTP().newCursor()) {
                cell.insertNewTbl(cursor);
            }
            var footer = doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            var pageNumber = footer.createParagraph().getCTP().addNewFldSimple();
            pageNumber.setInstr(" PAGE ");
            pageNumber.addNewR().addNewT().setStringValue("1");
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
