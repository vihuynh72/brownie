package io.github.vihuynh72.brownie.api.prepare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vihuynh72.brownie.api.document.docx.RawDocx;
import io.github.vihuynh72.brownie.api.document.pdf.PdfFormFixtures;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every sample form in every word-processing format, through the real
 * endpoints, Postgres, Azurite, ClamAV and the sandboxed converter and
 * renderer: the upload opens as a fillable copy with found spots, those
 * spots become a template's fields as they are, the template activates
 * with a real baseline render, and a document made from it compiles with
 * every value in place. Also the replay, the refusals, keeping found spots,
 * a form with nothing to fill, one workspace not reaching another's, and a
 * PDF, which opens as it is with its places as boxes on its page.
 *
 * <p>The renderer image is {@code BROWNIE_RENDER_IMAGE} (or the {@code
 * brownie.render.image} system property) when set, else the pinned one;
 * converting .doc, .rtf and .odt needs an image built with the converter's
 * hardening, such as {@code brownie-spike-renderer:fill-any-document}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class FillableFormIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-fillable-form-integration";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        registry.add("brownie.render.image", FillableFormIntegrationTest::imageUnderTest);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @ParameterizedTest
    @ValueSource(strings = {
            "membership-application.docx", "membership-application.doc", "membership-application.rtf",
            "membership-application.odt", "membership-application.ott",
            "equipment-request.docx", "equipment-request.doc", "equipment-request.rtf",
            "equipment-request.odt", "equipment-request.ott",
            "reference-letter.docx", "reference-letter.doc", "reference-letter.rtf",
            "reference-letter.odt", "reference-letter.ott"})
    void eachSampleFormOpensAsAFillableDocumentWhoseSpotsFillWithTheirValues(String fileName) throws Exception {
        Member member = signIn("subject-sample-" + fileName.replace('.', '-'));
        long upload = uploadAndFinalize(member, Files.readAllBytes(sampleForm(fileName)), fileName);

        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, upload)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());

        boolean converted = !fileName.endsWith(".docx");
        assertThat(form.get("kind").asText()).isEqualTo("DOCX");
        assertThat(form.get("sourceArtifactId").asLong()).isEqualTo(upload);
        assertThat(form.get("converted").asBoolean()).isEqualTo(converted);
        assertThat(form.get("extraction").get("status").asText()).isEqualTo("COMPLETE");
        assertThat(form.get("spotNaming").asText()).isEqualTo("RULES");
        assertThat(form.get("spots").size()).as(form.toString()).isGreaterThanOrEqualTo(10);
        assertThat(codes(form)).contains("SPOTS_FOUND");
        if (converted) {
            assertThat(codes(form)).contains("CONVERTED");
        }
        for (JsonNode spot : form.get("spots")) {
            assertThat(spot.get("origin").asText()).isEqualTo("FOUND_BY_BROWNIE");
            assertThat(spot.get("binding").get("kind").asText()).isEqualTo("CONTENT_CONTROL_TAG");
            assertThat(spot.get("binding").get("tag").asText()).isEqualTo(spot.get("fieldId").asText());
            assertThat(spot.get("namedBy").asText()).isEqualTo("RULES");
        }

        long templateVersionId = templateFromSpots(member, form, false);
        JsonNode manifest = createAndCompile(member, form, templateVersionId);

        assertThat(manifest.get("allIntegrityChecksPassed").asBoolean()).as(manifest.toString()).isTrue();
        assertThat(manifest.get("integrityFindings")).isNotEmpty();
        for (JsonNode finding : manifest.get("integrityFindings")) {
            assertThat(finding.get("foundInDocx").asBoolean()).as(finding.toString()).isTrue();
            assertThat(finding.get("foundInPdf").asBoolean()).as(finding.toString()).isTrue();
        }
    }

    @Test
    void askingAgainAnswersWithTheSameCopyAndGetReadsItWithoutMakingAnything() throws Exception {
        Member member = signIn("subject-replay");
        long upload = uploadAndFinalize(member, Files.readAllBytes(sampleForm("membership-application.docx")), "membership.docx");

        mockMvc.perform(get(fillableFormPath(member, upload)).cookie(member.session())).andExpect(status().isNotFound());
        String first = mockMvc.perform(post(fillableFormPath(member, upload)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String again = mockMvc.perform(post(fillableFormPath(member, upload)).cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String read = mockMvc.perform(get(fillableFormPath(member, upload)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(OBJECT_MAPPER.readTree(again)).isEqualTo(OBJECT_MAPPER.readTree(first));
        assertThat(OBJECT_MAPPER.readTree(read)).isEqualTo(OBJECT_MAPPER.readTree(first));

        // Another workspace reaches neither the upload nor what was made of it.
        Member outsider = signIn("subject-replay-outsider");
        String outsiderPath = "/api/v1/workspaces/" + outsider.workspaceId() + "/artifacts/" + upload + "/fillable-form";
        mockMvc.perform(post(outsiderPath).cookie(outsider.session()).with(csrf())).andExpect(status().isNotFound());
        mockMvc.perform(get(outsiderPath).cookie(outsider.session())).andExpect(status().isNotFound());
        mockMvc.perform(post(fillableFormPath(member, upload)).cookie(outsider.session()).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void aFileWithCommentsAndTrackedChangesSaysSoInItsNotices() throws Exception {
        Member member = signIn("subject-tracked");
        byte[] docx = RawDocx.builder()
                .document("<w:p><w:r><w:t xml:space=\"preserve\">Full name: </w:t></w:r>"
                        + "<w:ins w:id=\"1\" w:author=\"Ana\" w:date=\"2026-01-01T00:00:00Z\"><w:r><w:t>________</w:t></w:r></w:ins>"
                        + "<w:del w:id=\"2\" w:author=\"Ana\" w:date=\"2026-01-01T00:00:00Z\"><w:r><w:delText>old</w:delText></w:r></w:del></w:p>"
                        + "<w:p><w:commentRangeStart w:id=\"0\"/><w:r><w:t xml:space=\"preserve\">Town: ______</w:t></w:r>"
                        + "<w:commentRangeEnd w:id=\"0\"/><w:r><w:commentReference w:id=\"0\"/></w:r></w:p>")
                .part("word/comments.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml",
                        RawDocx.wordRoot("comments", "<w:comment w:id=\"0\" w:author=\"Ben\"><w:p><w:r><w:t>Check this</w:t></w:r></w:p></w:comment>"))
                .documentRelationship("rIdComments", RawDocx.RELATIONSHIP_TYPE_BASE + "comments", "comments.xml", false)
                .build();
        long upload = uploadAndFinalize(member, docx, "reviewed.docx");

        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, upload)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());

        assertThat(codes(form)).contains("TRACKED_CHANGES_AND_COMMENTS");
        assertThat(fieldIds(form)).containsExactly("full.name", "town");
    }

    @Test
    void aPlainLetterWithNothingToFillOpensWithNoSpotsAndActivatesOnlyWhenAsked() throws Exception {
        Member member = signIn("subject-plain-letter");
        byte[] docx = RawDocx.builder()
                .document("<w:p><w:r><w:t>Dear friend,</w:t></w:r></w:p><w:p><w:r><w:t>Thank you for helping at the garden.</w:t></w:r></w:p>")
                .build();
        long upload = uploadAndFinalize(member, docx, "letter.docx");

        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, upload)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());

        assertThat(form.get("spots")).isEmpty();
        assertThat(codes(form)).containsExactly("NO_SPOTS_FOUND");
        assertThat(form.get("rulesOnlyReason").asText()).isEqualTo("NO_CANDIDATES");
        long templateId = createDraft(member, form);
        mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"expectedVersionNumber\":1}"))
                .andExpect(status().isConflict());
        JsonNode activated = readJson(mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions")
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"expectedVersionNumber\":1,\"allowNoPlaces\":true}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(activated.get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(activated.get("fields")).isEmpty();
    }

    @Test
    void foundSpotsCanBeKeptOneByOneOrAllAtOnce() throws Exception {
        Member member = signIn("subject-keep");
        long upload = uploadAndFinalize(member, Files.readAllBytes(sampleForm("reference-letter.docx")), "letter.docx");
        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, upload)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        long templateId = createDraft(member, form);
        JsonNode draft = readJson(mockMvc.perform(put(templatesPath(member) + "/" + templateId + "/draft/bindings")
                        .cookie(member.session()).with(csrf()).contentType("application/json").content(bindingsBody(form, 1)))
                .andExpect(status().isOk()).andReturn());
        List<String> ids = fieldIds(form);
        String templatePath = templatesPath(member) + "/" + templateId;

        mockMvc.perform(put(templatePath + "/fields/" + ids.get(0) + "/review").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"decision\":\"KEPT\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(templatePath + "/fields/" + ids.get(0) + "/review").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"decision\":\"KEPT\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(templatePath + "/field-reviews").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"fieldIds\":[\"" + ids.get(1) + "\",\"" + ids.get(2) + "\"]}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put(templatePath + "/fields/no.such.field/review").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"decision\":\"KEPT\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put(templatePath + "/fields/" + ids.get(3) + "/review").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"decision\":\"REMOVED\"}"))
                .andExpect(status().isBadRequest());

        JsonNode version = readJson(mockMvc.perform(get(templatePath + "/versions/" + draft.get("id").asLong()).cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        List<String> kept = new ArrayList<>();
        version.get("acceptedFieldIds").forEach(id -> kept.add(id.asText()));
        assertThat(kept).containsExactlyInAnyOrder(ids.get(0), ids.get(1), ids.get(2));
    }

    @Test
    void aPdfOpensAsItIsWithItsBlankAsABoxThatGoesBackAsATemplateFieldAsItIs() throws Exception {
        Member member = signIn("subject-flat-pdf");
        long pdf = uploadAndFinalize(member, tinyPdf(), "form.pdf");

        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, pdf)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());

        assertThat(form.get("kind").asText()).isEqualTo("PDF");
        assertThat(form.get("sourceArtifactId").asLong()).isEqualTo(pdf);
        assertThat(form.get("templateSourceArtifactId").asLong()).as("a PDF is filled as it is, so no copy is made").isEqualTo(pdf);
        assertThat(form.get("sourceFormat").asText()).isEqualTo("PDF");
        assertThat(form.get("converted").asBoolean()).isFalse();
        assertThat(form.get("extraction").get("status").asText()).isEqualTo("COMPLETE");
        assertThat(form.get("extraction").get("keptAsIs")).isEmpty();
        assertThat(codes(form)).contains("SPOTS_FOUND");
        assertThat(form.get("spots")).hasSize(1);
        JsonNode spot = form.get("spots").get(0);
        assertThat(spot.get("label").asText()).isEqualTo("Name");
        assertThat(spot.get("origin").asText()).isEqualTo("FOUND_BY_BROWNIE");
        assertThat(spot.get("docxControl").isNull()).isTrue();
        assertThat(spot.get("blankText").isNull()).isTrue();
        JsonNode binding = spot.get("binding");
        assertThat(binding.get("kind").asText()).isEqualTo("PAGE_BOX");
        assertThat(binding.get("tag").isNull()).isTrue();
        assertThat(binding.get("acroFormField").isNull()).isTrue();
        assertThat(binding.get("pageBox").get("page").asInt()).isEqualTo(1);
        assertThat(binding.get("pageBox").get("overflow").asText()).isEqualTo("SHRINK_TO_FIT");

        JsonNode again = readJson(mockMvc.perform(post(fillableFormPath(member, pdf)).cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(again).isEqualTo(form);
        // The first answer is kept, so reading it back names nothing and gives the same spots.
        JsonNode stored = readJson(mockMvc.perform(get(fillableFormPath(member, pdf)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(stored).isEqualTo(form);

        long versionId = templateFromSpots(member, form, false);
        JsonNode version = readJson(mockMvc.perform(get(templatesPath(member) + "/" + lastTemplateId + "/versions/" + versionId)
                        .cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(version.get("kind").asText()).isEqualTo("PDF");
        assertThat(version.get("pdfFormExtractionId").asLong()).isEqualTo(form.get("extraction").get("id").asLong());
        JsonNode field = version.get("fields").get(0);
        assertThat(field.get("fieldId").asText()).isEqualTo(spot.get("fieldId").asText());
        assertThat(field.get("bindingKind").asText()).isEqualTo("PAGE_BOX");
        assertThat(field.get("pageBox")).isEqualTo(binding.get("pageBox"));
    }

    @Test
    void whatCannotBeMadeFillableIsRefusedWithAReasonAPersonCanActOn() throws Exception {
        Member member = signIn("subject-refusals");
        long text = uploadAndFinalize(member, "Just some notes.".getBytes(StandardCharsets.UTF_8), "notes.txt");
        long lockedPdf = uploadAndFinalize(member, PdfFormFixtures.ownerPasswordOnlyForm(), "locked.pdf");
        long unfinished = readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads")
                        .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"filename\":\"late.docx\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        assertThat(problemCode(post(fillableFormPath(member, text)), member, 415)).isEqualTo("NOT_A_WORD_PROCESSING_DOCUMENT");
        JsonNode locked = readJson(mockMvc.perform(post(fillableFormPath(member, lockedPdf)).cookie(member.session()).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andReturn());
        assertThat(locked.get("code").asText()).isEqualTo("PDF_FORM_NOT_FILLABLE");
        assertThat(locked.get("reason").asText()).isEqualTo("ENCRYPTED");
        assertThat(locked.get("detail").asText()).startsWith("This PDF is locked");
        problemCode(post(fillableFormPath(member, unfinished)), member, 409);
        problemCode(post(fillableFormPath(member, 987654321L)), member, 404);
    }

    // ---------------------------------------------------------------- template, document and compile

    private long createDraft(Member member, JsonNode form) throws Exception {
        JsonNode created = readJson(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Form\",\"sourceArtifactId\":" + form.get("templateSourceArtifactId").asLong() + "}"))
                .andExpect(status().isCreated()).andReturn());
        return created.get("template").get("id").asLong();
    }

    /** Makes a template whose fields are the spots exactly as the upload step gave them, and activates it. */
    private long templateFromSpots(Member member, JsonNode form, boolean allowNoPlaces) throws Exception {
        long templateId = createDraft(member, form);
        mockMvc.perform(put(templatesPath(member) + "/" + templateId + "/draft/bindings").cookie(member.session()).with(csrf())
                        .contentType("application/json").content(bindingsBody(form, 1)))
                .andExpect(status().isOk());
        JsonNode activated = readJson(mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersionNumber\":2,\"allowNoPlaces\":" + allowNoPlaces + "}"))
                .andExpect(status().isCreated()).andReturn());
        lastTemplateId = templateId;
        return activated.get("id").asLong();
    }

    private long lastTemplateId;

    private static String bindingsBody(JsonNode form, int expectedVersionNumber) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("expectedVersionNumber", expectedVersionNumber);
        ArrayNode fields = body.putArray("fields");
        for (JsonNode spot : form.get("spots")) {
            ObjectNode field = fields.addObject();
            for (String name : List.of("fieldId", "label", "type", "cardinality", "requiredness", "origin", "docxControl", "blankText")) {
                if (spot.hasNonNull(name)) {
                    field.set(name, spot.get(name));
                }
            }
            field.set("binding", spot.get("binding"));
        }
        return body.toString();
    }

    private JsonNode createAndCompile(Member member, JsonNode form, long templateVersionId) throws Exception {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("title", "Filled form");
        body.put("templateId", lastTemplateId);
        body.put("templateVersionId", templateVersionId);
        body.put("initialRevisionReason", "initial draft");
        ObjectNode values = body.putObject("fields");
        int n = 0;
        for (JsonNode spot : form.get("spots")) {
            n++;
            ObjectNode value = values.putObject(spot.get("fieldId").asText());
            boolean date = spot.get("type").asText().equals("DATE");
            value.put("type", spot.get("type").asText());
            value.put("cardinality", spot.get("cardinality").asText());
            if (spot.get("cardinality").asText().equals("REPEATED")) {
                value.putArray("values").add(date ? "2026-03-05" : "Row value " + n);
            } else {
                value.put("value", date ? "2026-03-05" : "Value " + n);
            }
        }
        JsonNode created = readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/documents")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andReturn());
        long documentId = created.get("id").asLong();
        long revisionId = created.get("currentRevision").get("id").asLong();
        return readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/documents/" + documentId + "/revisions/"
                        + revisionId + "/compile").cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
    }

    // ---------------------------------------------------------------- helpers

    private record Member(Cookie session, long workspaceId) {
    }

    private Member signIn(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
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
        return new Member(new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8))),
                workspaceId);
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private long uploadAndFinalize(Member member, byte[] bytes, String filename) throws Exception {
        String uploads = "/api/v1/workspaces/" + member.workspaceId() + "/uploads";
        long artifactId = readJson(mockMvc.perform(post(uploads).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"filename\":\"" + filename + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put(uploads + "/" + artifactId + "/content").cookie(member.session()).with(csrf())
                        .contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk());
        JsonNode completed = readJson(mockMvc.perform(post(uploads + "/" + artifactId + "/complete").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(completed.get("status").asText()).as(filename).isEqualTo("READY");
        return artifactId;
    }

    private String problemCode(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Member member, int status) throws Exception {
        MvcResult result = mockMvc.perform(request.cookie(member.session()).with(csrf())).andExpect(status().is(status)).andReturn();
        return readJson(result).get("code").asText();
    }

    private static byte[] tinyPdf() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Name: ________");
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> codes(JsonNode form) {
        List<String> codes = new ArrayList<>();
        form.get("notices").forEach(notice -> codes.add(notice.get("code").asText()));
        return codes;
    }

    private static List<String> fieldIds(JsonNode form) {
        List<String> ids = new ArrayList<>();
        form.get("spots").forEach(spot -> ids.add(spot.get("fieldId").asText()));
        return ids;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String fillableFormPath(Member member, long artifactId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/artifacts/" + artifactId + "/fillable-form";
    }

    private static String templatesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/templates";
    }

    private static String imageUnderTest() {
        String configured = System.getProperty("brownie.render.image", System.getenv("BROWNIE_RENDER_IMAGE"));
        return configured == null || configured.isBlank() ? "brownie-spike-renderer:pinned" : configured.trim();
    }

    private static Path sampleForm(String fileName) {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("fixtures/public/forms"))) {
            current = current.getParent();
        }
        assertThat(current).as("repository root").isNotNull();
        Path path = current.resolve("fixtures/public/forms").resolve(fileName);
        assertThat(path).isRegularFile();
        return path;
    }
}
