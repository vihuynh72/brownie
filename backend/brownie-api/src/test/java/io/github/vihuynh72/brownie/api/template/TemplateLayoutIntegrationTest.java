package io.github.vihuynh72.brownie.api.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the layout route end to end for both built-in templates against
 * real infrastructure (Postgres, Azurite, ClamAV and the isolated renderer
 * that activating a template depends on), the same pattern {@code
 * BuiltInTemplateProvisioningIntegrationTest} uses: the page it describes
 * is read from the template's own stored file, fill spots land where the
 * filler writes, and a version from another workspace is not found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class TemplateLayoutIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-template-layout";

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
    private ArtifactRepository artifactRepository;

    @Autowired
    private BlobStore blobStore;

    @Test
    void theTableLedBuiltInIsDrawnWithItsTextStylesFillSpotsAndRepeatingRow() throws Exception {
        Cookie session = signInWithBuiltIns("subject-layout-table");
        long workspaceId = workspaceOf("subject-layout-table");
        long[] template = findTemplate(session, workspaceId, "Table-led meeting minutes");

        JsonNode layout = readJson(mockMvc.perform(get(layoutPath(workspaceId, template[0], template[1])).cookie(session))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(layout.get("templateId").asLong()).isEqualTo(template[0]);
        assertThat(layout.get("versionId").asLong()).isEqualTo(template[1]);
        assertThat(layout.get("parserVersion").asText()).isEqualTo("brownie-docx-graph-v3+poi-5.5.1");
        assertThat(layout.get("unplacedFieldIds")).isEmpty();
        List<String> partKinds = new ArrayList<>();
        layout.get("parts").forEach(part -> partKinds.add(part.get("kind").asText()));
        assertThat(partKinds).containsExactly("MAIN_DOCUMENT", "HEADER", "FOOTER");

        JsonNode blocks = layout.get("parts").get(0).get("blocks");
        assertThat(blocks.get(0).get("inlines").get(0).get("kind").asText()).isEqualTo("IMAGE");

        JsonNode heading = blocks.get(1);
        assertThat(heading.get("kind").asText()).isEqualTo("PARAGRAPH");
        assertThat(heading.get("alignment").asText()).isEqualTo("START");
        assertThat(heading.get("rows").isNull()).isTrue();
        JsonNode headingText = heading.get("inlines").get(0);
        assertThat(headingText.get("kind").asText()).isEqualTo("TEXT");
        assertThat(headingText.get("text").asText()).isEqualTo("Meeting Minutes");
        assertThat(headingText.get("style").get("bold").asBoolean()).isTrue();
        assertThat(headingText.get("style").get("fontSizeHalfPoints").asInt()).isEqualTo(32);
        assertThat(headingText.get("style").get("fontFamily").asText()).isEqualTo("Liberation Sans");
        assertThat(headingText.get("style").get("colorHex").asText()).isEqualTo("000000");

        JsonNode titleLine = blocks.get(2).get("inlines");
        assertThat(titleLine.get(0).get("text").asText()).isEqualTo("Title: ");
        assertThat(titleLine.get(0).get("style").get("bold").asBoolean()).isTrue();
        JsonNode titleSpot = titleLine.get(1);
        assertThat(titleSpot.get("kind").asText()).isEqualTo("FILL_SPOT");
        assertThat(titleSpot.get("fieldId").asText()).isEqualTo("meeting.title");
        assertThat(titleSpot.get("placeholder").asText()).isEqualTo("[meeting title]");
        assertThat(titleSpot.get("text").isNull()).isTrue();
        assertThat(titleSpot.get("style").get("bold").isNull()).isTrue();
        assertThat(titleSpot.get("style").get("fontSizeHalfPoints").asInt()).isEqualTo(22);

        JsonNode table = null;
        for (JsonNode block : blocks) {
            if (block.get("kind").asText().equals("TABLE")) {
                table = block;
            }
        }
        assertThat(table).isNotNull();
        assertThat(table.get("inlines").isNull()).isTrue();
        assertThat(table.get("repeating").asBoolean()).isFalse();
        JsonNode rows = table.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("repeating").asBoolean()).isFalse();
        assertThat(rows.get(0).get("cells").get(0).get("blocks").get(0).get("inlines").get(0).get("text").asText()).isEqualTo("Task");
        assertThat(rows.get(1).get("repeating").asBoolean()).isTrue();
        List<String> rowFieldIds = new ArrayList<>();
        rows.get(1).get("cells").forEach(cell -> rowFieldIds.add(cell.get("blocks").get(0).get("inlines").get(0).get("fieldId").asText()));
        assertThat(rowFieldIds).containsExactly("action.item.task", "action.item.owner", "action.item.due");

        JsonNode header = layout.get("parts").get(1).get("blocks").get(0).get("inlines").get(0);
        assertThat(header.get("text").asText()).isEqualTo("Brownie Meeting Minutes Template");
        assertThat(header.get("style").get("bold").asBoolean()).isTrue();
        JsonNode footer = layout.get("parts").get(2).get("blocks").get(0).get("inlines");
        assertThat(footer).hasSize(1);
        assertThat(footer.get(0).get("text").asText()).isEqualTo("Page 1");
    }

    @Test
    void theFlowingBuiltInRepeatsItsActionItemParagraphAndHasNoTable() throws Exception {
        Cookie session = signInWithBuiltIns("subject-layout-flowing");
        long workspaceId = workspaceOf("subject-layout-flowing");
        long[] template = findTemplate(session, workspaceId, "Flowing meeting minutes");

        JsonNode layout = readJson(mockMvc.perform(get(layoutPath(workspaceId, template[0], template[1])).cookie(session))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode blocks = layout.get("parts").get(0).get("blocks");
        List<String> repeatingFieldIds = new ArrayList<>();
        List<String> fillSpotFieldIds = new ArrayList<>();
        for (JsonNode block : blocks) {
            assertThat(block.get("kind").asText()).isEqualTo("PARAGRAPH");
            for (JsonNode inline : block.get("inlines")) {
                if (inline.get("kind").asText().equals("FILL_SPOT")) {
                    fillSpotFieldIds.add(inline.get("fieldId").asText());
                    if (block.get("repeating").asBoolean()) {
                        repeatingFieldIds.add(inline.get("fieldId").asText());
                    }
                }
            }
        }
        assertThat(repeatingFieldIds).containsExactly("action.item.task", "action.item.owner", "action.item.due");
        assertThat(fillSpotFieldIds).containsExactly(
                "meeting.title", "meeting.organization", "meeting.date", "meeting.location", "meeting.attendees",
                "meeting.decisions", "action.item.task", "action.item.owner", "action.item.due");
        assertThat(layout.get("unplacedFieldIds")).isEmpty();
    }

    @Test
    void aVersionFromAnotherWorkspaceIsNotFoundAndAnotherWorkspacesRouteIsForbidden() throws Exception {
        Cookie ownerSession = signInWithBuiltIns("subject-layout-owner");
        long ownerWorkspaceId = workspaceOf("subject-layout-owner");
        long[] ownersTemplate = findTemplate(ownerSession, ownerWorkspaceId, "Flowing meeting minutes");
        Cookie strangerSession = signInWithBuiltIns("subject-layout-stranger");
        long strangerWorkspaceId = workspaceOf("subject-layout-stranger");

        mockMvc.perform(get(layoutPath(strangerWorkspaceId, ownersTemplate[0], ownersTemplate[1])).cookie(strangerSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get(layoutPath(ownerWorkspaceId, ownersTemplate[0], ownersTemplate[1])).cookie(strangerSession))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(layoutPath(ownerWorkspaceId, ownersTemplate[0], ownersTemplate[1] + 100_000)).cookie(ownerSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** The stored bytes are swapped for something that is not a Word file at all, as a damaged store would leave them. */
    @Test
    void aTemplateWhoseStoredFileNoLongerReadsAsAWordDocumentIsUnprocessable() throws Exception {
        Cookie session = signInWithBuiltIns("subject-layout-unreadable");
        long workspaceId = workspaceOf("subject-layout-unreadable");
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-layout-unreadable").orElseThrow().id();
        long[] template = findTemplate(session, workspaceId, "Flowing meeting minutes");
        JsonNode version = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates/" + template[0]
                        + "/versions/" + template[1]).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        String blobKey = artifactRepository.find(workspaceId, userId, version.get("sourceArtifactId").asLong()).orElseThrow().blobKey();
        byte[] notAWordFile = "this is not a word document".getBytes(StandardCharsets.UTF_8);
        blobStore.delete(blobKey);
        blobStore.writeNewAndDigest(blobKey, new ByteArrayInputStream(notAWordFile), notAWordFile.length);

        mockMvc.perform(get(layoutPath(workspaceId, template[0], template[1])).cookie(session))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("TEMPLATE_LAYOUT_UNAVAILABLE"));
    }

    /**
     * A template made while an earlier reader took its file, from a file
     * today's reader refuses (here a tracked paragraph mark, as pressing
     * Enter with Track Changes on leaves): its page is still drawn, from the
     * reading it was made with, and its documents still validate, because
     * the filled copy is refused only for what its template already has.
     */
    @Test
    void aTemplateMadeFromAFileTodaysReaderRefusesIsStillDrawnAndItsDocumentsValidate() throws Exception {
        Cookie session = signInWithBuiltIns("subject-layout-older");
        long workspaceId = workspaceOf("subject-layout-older");
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, "subject-layout-older").orElseThrow().id();
        long[] template = findTemplate(session, workspaceId, "Flowing meeting minutes");
        JsonNode version = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates/" + template[0]
                        + "/versions/" + template[1]).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        String blobKey = artifactRepository.find(workspaceId, userId, version.get("sourceArtifactId").asLong()).orElseThrow().blobKey();
        byte[] original;
        try (InputStream in = blobStore.openStream(blobKey)) {
            original = in.readAllBytes();
        }
        byte[] withTrackedMark = withFirstParagraph(original,
                "<w:p><w:pPr><w:rPr><w:ins w:id=\"9001\" w:author=\"Reviewer\" w:date=\"2020-01-01T00:00:00Z\"/></w:rPr></w:pPr></w:p>");
        blobStore.delete(blobKey);
        blobStore.writeNewAndDigest(blobKey, new ByteArrayInputStream(withTrackedMark), withTrackedMark.length);

        mockMvc.perform(get(layoutPath(workspaceId, template[0], template[1])).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(template[1]));

        String document = "{\"title\":\"Weekly Sync\",\"templateId\":" + template[0] + ",\"templateVersionId\":" + template[1]
                + ",\"fields\":{\"meeting.title\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Weekly Sync\"}},"
                + "\"initialRevisionReason\":\"Created for a layout test.\"}";
        JsonNode created = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents")
                        .cookie(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(document))
                .andExpect(status().isCreated())
                .andReturn());
        JsonNode validated = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents/" + created.get("id").asLong()
                                + "/validate")
                        .cookie(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + created.get("currentRevision").get("id").asLong() + "}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn());
        List<String> codes = new ArrayList<>();
        validated.get("findings").forEach(finding -> codes.add(finding.get("code").asText()));
        assertThat(codes).doesNotContain("PACKAGE_INTEGRITY_FAILURE");
    }

    /** The same Word file with {@code paragraph} put first in its body. */
    private static byte[] withFirstParagraph(byte[] docx, String paragraph) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(content, StandardCharsets.UTF_8);
                    Matcher body = Pattern.compile("<w:body(?:\\s[^>]*)?>").matcher(xml);
                    assertThat(body.find()).isTrue();
                    content = (xml.substring(0, body.end()) + paragraph + xml.substring(body.end())).getBytes(StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new ZipEntry(entry.getName()));
                zip.write(content);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private Cookie signInWithBuiltIns(String subject) {
        Cookie session = loginAndGetSessionCookie(subject);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceOf(subject), userId);
        return session;
    }

    private long workspaceOf(String subject) {
        return ensureWorkspace(subject).id();
    }

    private long[] findTemplate(Cookie session, long workspaceId, String displayName) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        for (JsonNode template : templates) {
            if (template.get("displayName").asText().equals(displayName)) {
                return new long[] {template.get("id").asLong(), template.get("currentActiveVersionId").asLong()};
            }
        }
        throw new AssertionError(displayName + " was not provisioned: " + templates);
    }

    private static String layoutPath(long workspaceId, long templateId, long versionId) {
        return "/api/v1/workspaces/" + workspaceId + "/templates/" + templateId + "/versions/" + versionId + "/layout";
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
