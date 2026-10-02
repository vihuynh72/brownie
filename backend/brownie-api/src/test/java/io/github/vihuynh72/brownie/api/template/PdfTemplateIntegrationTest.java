package io.github.vihuynh72.brownie.api.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vihuynh72.brownie.api.document.pdf.PdfFormFixtures;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.document.UnsupportedPdfFormReason;
import io.github.vihuynh72.brownie.core.document.UnusablePdfFormException;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparation;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparationService;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.prepare.PreparedPdfSpot;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.text.PDFTextStripper;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A PDF made a form end to end, through real HTTP dispatch and a real
 * Postgres, Azurite and ClamAV, and without the Word renderer at all: the
 * public fillable and flat application forms are uploaded, prepared (their
 * reading kept, their places found and named), made a template from the
 * places found, activated against a sample fill, filled with a document's
 * values (Vietnamese included), compiled, validated, approved and exported
 * as a PDF, and the exported PDF read back holds the values. A Word export
 * is never offered for one, deleting its document or the whole workspace
 * still works, a locked PDF is refused with its reason, and a scan gets no
 * places. The upload step opens a PDF the same way, its places ready to go
 * back as a template's fields as they are; a place in a Word form's text
 * means nothing on a PDF form and is refused in words, and a PDF form's
 * documents move between its versions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.public-origin=http://localhost:8081",
        "brownie.web.origin=http://localhost:5173",
        "brownie.connectors.google.client-id=stand-in-client.apps.googleusercontent.com",
        "brownie.connectors.google.client-secret=stand-in-client-secret-value",
        "brownie.connectors.token-key-id=test-key",
        "brownie.connectors.google.actions-offered=true"})
@DockerTest
class PdfTemplateIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-pdf-template-integration";
    private static final String TOKEN_KEY = randomKey();

    /** "Nguyen Thi Minh Khai" with its Vietnamese marks, and a street in Ha Noi. */
    private static final String VIETNAMESE_NAME = "Nguy\u1ec5n Th\u1ecb Minh Khai";
    private static final String VIETNAMESE_STREET = "12 Ph\u1ed1 H\u00e0ng B\u00e0i, H\u00e0 N\u1ed9i";

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
        registry.add("brownie.connectors.token-key", () -> TOKEN_KEY);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private PdfFormPreparationService preparationService;

    @Autowired
    private PdfFormReader pdfFormReader;

    // ---- the whole way, for a fillable form ----

    @Test
    void aFillableFormGoesFromUploadToAnExportedPdfHoldingTheValues() throws Exception {
        Member member = signIn("subject-pdf-fillable");
        long artifactId = upload(member, publicPdf("fillable-application.pdf"), "application.pdf");

        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);

        Map<String, PreparedPdfSpot> byFormField = new LinkedHashMap<>();
        for (PreparedPdfSpot spot : prepared.spots()) {
            if (spot.definition().binding() instanceof FieldBindingTarget.AcroFormField(String name)) {
                byFormField.put(name, spot);
            }
        }
        assertThat(byFormField).containsOnlyKeys("fullName", "address", "zip", "email", "birthDate", "applicant.phone");
        assertThat(byFormField.values()).allSatisfy(spot -> assertThat(spot.definition().effectiveOrigin()).isEqualTo(SpotOrigin.FORM));
        assertThat(byFormField.get("email").requiredHint()).isTrue();
        assertThat(byFormField.get("fullName").definition().label()).isEqualTo("Full name");
        assertThat(byFormField.get("birthDate").definition().type()).isEqualTo(FieldType.DATE);
        assertThat(prepared.notices()).contains(new PreparationNotice(PreparationNotice.PDF_FIELDS_LEFT, 5, null));

        Template template = createTemplate(member, artifactId, "Membership application");
        assertThat(template.draft().get("kind").asText()).isEqualTo("PDF");
        assertThat(template.draft().get("pdfFormExtractionId").asLong()).isEqualTo(prepared.formExtractionId());
        assertThat(template.draft().get("extractionVersionId").isNull()).isTrue();
        long versionId = bindAndActivate(member, template.id(), prepared.spots());

        JsonNode layout = read(mockMvc.perform(get(templatesPath(member) + "/" + template.id() + "/versions/" + versionId + "/layout")
                .cookie(member.session())).andExpect(status().isOk()).andReturn());
        assertThat(layout.get("kind").asText()).isEqualTo("PDF");
        assertThat(layout.get("parts")).isEmpty();
        assertThat(layout.get("pdf").get("sourceArtifactId").asLong()).isEqualTo(artifactId);
        assertThat(layout.get("pdf").get("pages").get(0).get("width").asDouble()).isEqualTo(612.0);
        assertThat(layout.get("pdf").get("pages").get(0).get("lines").get(0).get("text").asText()).isEqualTo("Membership application");
        String nameId = byFormField.get("fullName").definition().fieldId();
        JsonNode nameSpot = spotOf(layout, nameId);
        assertThat(nameSpot.get("bindingKind").asText()).isEqualTo("ACROFORM_FIELD");
        assertThat(nameSpot.get("box").get("x").asDouble()).isEqualTo(150.0);
        assertThat(nameSpot.get("style").isNull()).isTrue();

        JsonNode suggestion = read(mockMvc.perform(post(templatesPath(member) + "/" + template.id() + "/versions/" + versionId + "/box-suggestion")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"pageNumber\":1,\"lineIndex\":0}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(suggestion.get("box").get("width").asDouble()).isPositive();
        assertThat(suggestion.get("style").get("font").asText()).isEqualTo("SANS");
        mockMvc.perform(post(templatesPath(member) + "/" + template.id() + "/versions/" + versionId + "/box-suggestion")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"pageNumber\":9,\"lineIndex\":0}"))
                .andExpect(status().isBadRequest());

        Map<String, String[]> values = new LinkedHashMap<>();
        values.put(nameId, new String[] {"TEXT", VIETNAMESE_NAME});
        values.put(byFormField.get("address").definition().fieldId(), new String[] {"TEXT", VIETNAMESE_STREET});
        values.put(byFormField.get("email").definition().fieldId(), new String[] {"TEXT", "khai@example.org"});
        values.put(byFormField.get("birthDate").definition().fieldId(), new String[] {"DATE", "1990-09-02"});
        Exported exported = fillAndExport(member, template.id(), versionId, values);

        assertThat(exported.compilation().get("docxArtifactId").isNull()).isTrue();
        assertThat(exported.compilation().get("allIntegrityChecksPassed").asBoolean()).isTrue();
        assertThat(exported.receipt().get("docxArtifactId").isNull()).isTrue();
        assertThat(exported.receipt().get("isCompletePair").asBoolean()).isFalse();
        try (PDDocument pdf = Loader.loadPDF(download(member, exported.receipt().get("pdfArtifactId").asLong()))) {
            PDAcroForm form = pdf.getDocumentCatalog().getAcroForm(null);
            assertThat(form.getField("fullName").getValueAsString()).isEqualTo(VIETNAMESE_NAME);
            assertThat(form.getField("address").getValueAsString()).isEqualTo(VIETNAMESE_STREET);
            assertThat(form.getField("email").getValueAsString()).isEqualTo("khai@example.org");
            assertThat(form.getField("birthDate").getValueAsString()).isEqualTo("September 2, 1990");
            assertThat(form.getField("reference").getValueAsString()).isEqualTo("REF-001");
        }

        // A PDF form is exported as a PDF only, and a Word file or a Google Doc is never saved from it.
        for (String format : List.of("DOCX", "BOTH")) {
            JsonNode refused = read(mockMvc.perform(post(documentPath(member, exported.documentId()) + "/export-approval")
                            .cookie(member.session()).with(csrf()).contentType("application/json")
                            .content("{\"validationManifestId\":" + exported.manifest().get("id").asLong() + ",\"format\":\"" + format + "\"}"))
                    .andExpect(status().isUnprocessableEntity()).andReturn());
            assertThat(refused.get("code").asText()).isEqualTo("EXPORT_FORMAT_NOT_OFFERED");
        }
        for (String kind : List.of("WORD_FILE", "GOOGLE_DOC")) {
            JsonNode refused = read(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/actions/drive-saves")
                            .cookie(member.session()).with(csrf()).contentType("application/json")
                            .content("{\"documentId\":" + exported.documentId() + ",\"kind\":\"" + kind + "\"}"))
                    .andExpect(status().isConflict()).andReturn());
            assertThat(refused.get("reason").asText()).isEqualTo("FORMAT_NOT_EXPORTED");
        }
    }

    // ---- through the upload step ----

    @Test
    void aFillablePdfOpensThroughTheUploadStepWithTheFormsOwnFieldsAsSpotsThatBecomeTheTemplatesFields() throws Exception {
        Member member = signIn("subject-pdf-upload-step");
        long artifactId = upload(member, publicPdf("fillable-application.pdf"), "application.pdf");

        JsonNode form = read(mockMvc.perform(post(fillableFormPath(member, artifactId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());

        assertThat(form.get("kind").asText()).isEqualTo("PDF");
        assertThat(form.get("sourceArtifactId").asLong()).isEqualTo(artifactId);
        assertThat(form.get("templateSourceArtifactId").asLong()).isEqualTo(artifactId);
        assertThat(form.get("sourceFormat").asText()).isEqualTo("PDF");
        assertThat(form.get("converted").asBoolean()).isFalse();
        JsonNode extraction = form.get("extraction");
        assertThat(extraction.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(extraction.get("parserVersion").asText()).isEqualTo(pdfFormReader.parserVersion());
        assertThat(extraction.get("id").asLong())
                .isEqualTo(countAsOwner("SELECT max(id) FROM pdf_form_extraction_version WHERE artifact_id = ?", artifactId));
        assertThat(extraction.get("keptAsIs")).isEmpty();
        Map<String, JsonNode> byFormField = new LinkedHashMap<>();
        for (JsonNode spot : form.get("spots")) {
            JsonNode binding = spot.get("binding");
            assertThat(binding.get("kind").asText()).as(spot.toString()).isEqualTo("ACROFORM_FIELD");
            assertThat(binding.get("tag").isNull()).isTrue();
            assertThat(binding.get("pageBox").isNull()).isTrue();
            assertThat(spot.get("origin").asText()).isEqualTo("FORM");
            assertThat(spot.get("requiredness").asText()).isEqualTo("OPTIONAL");
            assertThat(spot.get("docxControl").isNull()).isTrue();
            assertThat(spot.get("blankText").isNull()).isTrue();
            assertThat(spot.get("namedBy").asText()).isEqualTo("RULES");
            byFormField.put(binding.get("acroFormField").asText(), spot);
        }
        assertThat(byFormField).containsOnlyKeys("fullName", "address", "zip", "email", "birthDate", "applicant.phone");
        assertThat(byFormField.get("fullName").get("label").asText()).isEqualTo("Full name");
        assertThat(byFormField.get("birthDate").get("type").asText()).isEqualTo("DATE");
        assertThat(byFormField.get("email").get("requiredHint").asBoolean()).isTrue();
        List<String> notices = new ArrayList<>();
        form.get("notices").forEach(notice -> notices.add(notice.get("code").asText() + " " + notice.get("count").asInt()));
        assertThat(notices).contains("SPOTS_FOUND 6", "PDF_FIELDS_LEFT 5");
        assertThat(form.get("spotNaming").asText()).isEqualTo("RULES");

        // Asking again reads nothing new: the same reading and the same places.
        JsonNode again = read(mockMvc.perform(post(fillableFormPath(member, artifactId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(again).isEqualTo(form);
        assertThat(countAsOwner("SELECT count(*) FROM pdf_form_extraction_version WHERE artifact_id = ?", artifactId)).isEqualTo(1);

        // The spots go back as the template's fields exactly as they came.
        Template template = createTemplate(member, form.get("templateSourceArtifactId").asLong(), "Membership application");
        assertThat(template.draft().get("pdfFormExtractionId").asLong()).isEqualTo(extraction.get("id").asLong());
        ObjectNode body = JSON.createObjectNode().put("expectedVersionNumber", 1);
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
        mockMvc.perform(put(templatesPath(member) + "/" + template.id() + "/draft/bindings").cookie(member.session()).with(csrf())
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk());
        JsonNode activated = read(mockMvc.perform(post(templatesPath(member) + "/" + template.id() + "/versions").cookie(member.session())
                        .with(csrf()).contentType("application/json").content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(activated.get("kind").asText()).isEqualTo("PDF");
        Map<String, String> boundTo = new LinkedHashMap<>();
        activated.get("fields").forEach(field -> boundTo.put(field.get("acroFormField").asText(), field.get("fieldId").asText()));
        Map<String, String> offered = new LinkedHashMap<>();
        byFormField.forEach((name, spot) -> offered.put(name, spot.get("fieldId").asText()));
        assertThat(boundTo).isEqualTo(offered);
    }

    // ---- fill spots and versions ----

    @Test
    void aWordPlaceIsRefusedOnAPdfFormAndItsDocumentsMoveBetweenItsVersions() throws Exception {
        Member member = signIn("subject-pdf-fill-spots");
        long artifactId = upload(member, publicPdf("fillable-application.pdf"), "application.pdf");
        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);
        Template template = createTemplate(member, artifactId, "Membership application");
        long firstVersionId = bindAndActivate(member, template.id(), prepared.spots());
        String nameId = fieldIdBoundTo(prepared, "fullName");
        String zipId = fieldIdBoundTo(prepared, "zip");
        Map<String, String[]> values = new LinkedHashMap<>();
        values.put(nameId, new String[] {"TEXT", "Ana"});
        values.put(zipId, new String[] {"TEXT", "10000"});
        JsonNode document = createDocument(member, template.id(), firstVersionId, values);
        long documentId = document.get("id").asLong();
        long revisionId = document.get("currentRevision").get("id").asLong();
        long versionsBefore = countAsOwner("SELECT count(*) FROM template_version WHERE template_id = ?", template.id());

        // A place in a Word paragraph means nothing on a PDF: a new spot there is a box on a page.
        String wordAnchor = "{\"part\":\"MAIN_DOCUMENT\",\"paragraphNodeId\":\"p0\",\"placement\":\"AT\",\"start\":0,\"end\":0,"
                + "\"anchorTextHash\":\"0000000000000000\",\"parserVersion\":\"brownie-docx-graph-v3+poi-5.5.1\"}";
        String change = "{\"kind\":\"ADD\",\"label\":\"Company\",\"anchor\":" + wordAnchor + "}";
        JsonNode refused = read(mockMvc.perform(post(documentPath(member, documentId) + "/fill-spots").cookie(member.session())
                        .with(csrf()).header("Idempotency-Key", "spots-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"templateVersionId\":" + firstVersionId
                                + ",\"changes\":[" + change + "]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("FILL_SPOT_CHANGE_INVALID");
        assertThat(refused.get("detail").asText()).startsWith("This form is a PDF, so a new fill spot is a box on its page.");
        assertThat(countAsOwner("SELECT count(*) FROM template_version WHERE template_id = ?", template.id()))
                .as("nothing was made").isEqualTo(versionsBefore);

        // A later version of the same form, without the zip code, as a correction of its places would make one.
        long secondVersionId = insertActivatedCopyWithout(firstVersionId, zipId);

        JsonNode moved = read(mockMvc.perform(post(documentPath(member, documentId) + "/template-version").cookie(member.session())
                        .with(csrf()).header("Idempotency-Key", "move-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"templateVersionId\":" + secondVersionId + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(moved.get("revision").get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(moved.get("previousTemplateVersionId").asLong()).isEqualTo(firstVersionId);
        assertThat(moved.get("droppedFieldIds")).extracting(JsonNode::asText).containsExactly(zipId);

        JsonNode back = read(mockMvc.perform(post(documentPath(member, documentId) + "/template-version").cookie(member.session())
                        .with(csrf()).header("Idempotency-Key", "move-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + moved.get("revision").get("id").asLong()
                                + ",\"templateVersionId\":" + firstVersionId + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(back.get("revision").get("templateVersionId").asLong()).isEqualTo(firstVersionId);
        assertThat(back.get("droppedFieldIds")).isEmpty();
    }

    // ---- the whole way, for a flat form ----

    @Test
    void aFlatFormsBlanksBecomeBoxesAndTheExportedPdfShowsTheValuesDrawnInThem() throws Exception {
        Member member = signIn("subject-pdf-flat");
        long artifactId = upload(member, publicPdf("flat-application.pdf"), "sign-up.pdf");

        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);

        assertThat(prepared.spots()).isNotEmpty();
        assertThat(prepared.spots()).allSatisfy(spot -> {
            assertThat(spot.definition().binding()).isInstanceOf(FieldBindingTarget.PageBox.class);
            assertThat(spot.definition().effectiveOrigin()).isEqualTo(SpotOrigin.FOUND_BY_BROWNIE);
        });
        assertThat(prepared.notices()).extracting(PreparationNotice::code).contains(PreparationNotice.SIGNATURE_LINES_LEFT);
        // The table's cells are named by their column's header, never by the course printed beside them.
        assertThat(prepared.spots()).extracting(spot -> spot.definition().label()).containsExactly(
                "Full name", "Date of birth", "Email", "Address",
                "Year (Painting)", "Course (row 2)", "Year (row 2)", "Course (row 3)", "Year (row 3)");
        FieldDefinition name = prepared.spots().stream().map(PreparedPdfSpot::definition)
                .filter(field -> "Full name".equals(field.label())).findFirst().orElseThrow();

        Template template = createTemplate(member, artifactId, "Volunteer sign-up");
        long versionId = bindAndActivate(member, template.id(), prepared.spots());

        Exported exported = fillAndExport(member, template.id(), versionId,
                Map.of(name.fieldId(), new String[] {"TEXT", VIETNAMESE_NAME}));

        assertThat(exported.manifest().get("docxArtifactId").isNull()).isTrue();
        try (PDDocument pdf = Loader.loadPDF(download(member, exported.receipt().get("pdfArtifactId").asLong()))) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains(VIETNAMESE_NAME).contains("Volunteer sign-up");
        }
    }

    // ---- deleting ----

    @Test
    void aPdfFormsDocumentCanBeDeletedForGoodAndItsWorkspaceDeleted() throws Exception {
        Member member = signIn("subject-pdf-deletion");
        long artifactId = upload(member, publicPdf("fillable-application.pdf"), "application.pdf");
        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);
        Template template = createTemplate(member, artifactId, "Membership application");
        long versionId = bindAndActivate(member, template.id(), prepared.spots());
        String nameId = prepared.spots().stream().map(PreparedPdfSpot::definition)
                .filter(field -> field.binding().equals(new FieldBindingTarget.AcroFormField("fullName"))).findFirst().orElseThrow().fieldId();
        Exported exported = fillAndExport(member, template.id(), versionId, Map.of(nameId, new String[] {"TEXT", "Ana"}));

        JsonNode trashed = read(mockMvc.perform(post(deletionsPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"DOCUMENT\",\"documentId\":" + exported.documentId() + "}"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode purged = read(mockMvc.perform(post(deletionsPath(member) + "/" + trashed.get("id").asLong() + "/purge")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(purged.get("state").asText()).isEqualTo("PURGED");
        for (String table : List.of("document_compilation", "validation_manifest", "export_receipt")) {
            assertThat(countAsOwner("SELECT count(*) FROM " + table + " WHERE document_id = ?", exported.documentId())).as(table).isZero();
        }
        // The template, its reading and its source stay: other documents may be made from them.
        assertThat(countAsOwner("SELECT count(*) FROM pdf_form_extraction_version WHERE artifact_id = ?", artifactId)).isEqualTo(1);

        mockMvc.perform(post(deletionsPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk());
        for (String table : List.of("pdf_form_extraction_version", "template_version", "template_baseline_render", "artifact")) {
            assertThat(countAsOwner("SELECT count(*) FROM " + table + " WHERE workspace_id = ?", member.workspaceId())).as(table).isZero();
        }
    }

    // ---- what is refused, and a scan ----

    @Test
    void aLockedPdfIsRefusedWithItsReasonWhenPreparedAndWhenMadeATemplate() throws Exception {
        Member member = signIn("subject-pdf-locked");
        long artifactId = upload(member, PdfFormFixtures.ownerPasswordOnlyForm(), "locked.pdf");

        assertThatThrownBy(() -> preparationService.prepare(member.workspaceId(), member.userId(), artifactId))
                .isInstanceOfSatisfying(UnusablePdfFormException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(UnsupportedPdfFormReason.ENCRYPTED));

        JsonNode problem = read(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"displayName\":\"Locked\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isUnprocessableEntity()).andReturn());
        assertThat(problem.get("code").asText()).isEqualTo("PDF_FORM_NOT_FILLABLE");
        assertThat(problem.get("reason").asText()).isEqualTo("ENCRYPTED");
        assertThat(problem.get("detail").asText()).startsWith("This PDF is locked");
    }

    @Test
    void aPdfNeverPreparedCannotBeMadeATemplate() throws Exception {
        Member member = signIn("subject-pdf-unprepared");
        long artifactId = upload(member, publicPdf("flat-application.pdf"), "sign-up.pdf");

        JsonNode problem = read(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"displayName\":\"Sign-up\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isUnprocessableEntity()).andReturn());
        assertThat(problem.get("code").asText()).isEqualTo("SOURCE_NOT_EXTRACTABLE");
    }

    @Test
    void aScanHasNoPlacesAndIsSaidToBeOne() throws Exception {
        Member member = signIn("subject-pdf-scan");
        long artifactId = upload(member, publicPdf("scanned-note.pdf"), "note.pdf");

        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);

        assertThat(prepared.spots()).isEmpty();
        assertThat(prepared.notices()).containsExactly(new PreparationNotice(PreparationNotice.SCANNED_PDF, 0, null));
        Template template = createTemplate(member, artifactId, "Scanned note");
        assertThat(template.draft().get("kind").asText()).isEqualTo("PDF");
    }

    // ---- steps ----

    private record Member(Cookie session, long workspaceId, long userId) {
    }

    private record Template(long id, JsonNode draft) {
    }

    private record Exported(long documentId, JsonNode compilation, JsonNode manifest, JsonNode receipt) {
    }

    private Template createTemplate(Member member, long artifactId, String name) throws Exception {
        JsonNode created = read(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"" + name + "\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated()).andReturn());
        return new Template(created.get("template").get("id").asLong(), created.get("draftVersion"));
    }

    /** Binds exactly the places the preparation found, as a page making a template from them would, and activates the draft. */
    private long bindAndActivate(Member member, long templateId, List<PreparedPdfSpot> spots) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("expectedVersionNumber", 1);
        ArrayNode fields = body.putArray("fields");
        spots.forEach(spot -> fields.add(fieldJson(spot.definition())));
        JsonNode bound = read(mockMvc.perform(put(templatesPath(member) + "/" + templateId + "/draft/bindings").cookie(member.session())
                        .with(csrf()).contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn());
        assertThat(bound.get("fields")).hasSize(spots.size());
        JsonNode activated = read(mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions").cookie(member.session())
                        .with(csrf()).contentType("application/json").content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(activated.get("kind").asText()).isEqualTo("PDF");
        return activated.get("id").asLong();
    }

    /** Creates a document with {@code values} (field id to type and value), then compiles, validates, approves a PDF export and exports. */
    private Exported fillAndExport(Member member, long templateId, long versionId, Map<String, String[]> values) throws Exception {
        JsonNode document = createDocument(member, templateId, versionId, values);
        long documentId = document.get("id").asLong();
        long revisionId = document.get("currentRevision").get("id").asLong();

        JsonNode compilation = read(mockMvc.perform(post(documentPath(member, documentId) + "/revisions/" + revisionId + "/compile")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        assertThat(compilation.get("pdfArtifactId").asLong()).isPositive();

        JsonNode manifest = read(mockMvc.perform(post(documentPath(member, documentId) + "/validate").cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "validate-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean()).as(manifest.get("findings").toString()).isFalse();
        assertThat(manifest.get("docxArtifactId").isNull()).isTrue();
        assertThat(manifest.get("pdfArtifactId").asLong()).isPositive();

        mockMvc.perform(post(documentPath(member, documentId) + "/export-approval").cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"PDF\"}"))
                .andExpect(status().isCreated());
        JsonNode receipt = read(mockMvc.perform(post(documentPath(member, documentId) + "/export").cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        assertThat(receipt.get("format").asText()).isEqualTo("PDF");
        assertThat(receipt.get("pdfArtifactId").asLong()).isEqualTo(manifest.get("pdfArtifactId").asLong());
        return new Exported(documentId, compilation, manifest, receipt);
    }

    /** Creates a document on {@code versionId} with {@code values} (field id to type and value). */
    private JsonNode createDocument(Member member, long templateId, long versionId, Map<String, String[]> values) throws Exception {
        ObjectNode create = JSON.createObjectNode()
                .put("title", "Filled form")
                .put("templateId", templateId)
                .put("templateVersionId", versionId)
                .put("initialRevisionReason", "initial draft");
        ObjectNode fields = create.putObject("fields");
        values.forEach((fieldId, typed) -> fields.putObject(fieldId).put("type", typed[0]).put("cardinality", "SCALAR").put("value", typed[1]));
        return read(mockMvc.perform(post(documentsPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID()).contentType("application/json")
                        .content(JSON.writeValueAsString(create)))
                .andExpect(status().isCreated()).andReturn());
    }

    /** A field as the template API's request takes it, the same shape its response gives back. */
    private static ObjectNode fieldJson(FieldDefinition field) {
        ObjectNode json = JSON.createObjectNode()
                .put("fieldId", field.fieldId())
                .put("type", field.type().name())
                .put("cardinality", field.cardinality().name())
                .put("requiredness", field.requiredness().name())
                .put("label", field.label())
                .put("origin", field.effectiveOrigin().name());
        ObjectNode binding = json.putObject("binding");
        switch (field.binding()) {
            case FieldBindingTarget.AcroFormField(String name) -> binding.put("kind", "ACROFORM_FIELD").put("acroFormField", name);
            case FieldBindingTarget.PageBox box -> {
                binding.put("kind", "PAGE_BOX");
                ObjectNode pageBox = binding.putObject("pageBox")
                        .put("page", box.page()).put("x", box.x()).put("y", box.y()).put("width", box.width()).put("height", box.height())
                        .put("multiline", box.multiline()).put("overflow", box.overflow().name());
                pageBox.putObject("style")
                        .put("font", box.style().family().name()).put("bold", box.style().bold()).put("sizePt", box.style().sizePt());
            }
            default -> throw new IllegalArgumentException("A PDF place is bound to a form field or a box, not " + field.binding());
        }
        return json;
    }

    private static String fieldIdBoundTo(PdfFormPreparation prepared, String formFieldName) {
        return prepared.spots().stream().map(PreparedPdfSpot::definition)
                .filter(field -> field.binding().equals(new FieldBindingTarget.AcroFormField(formFieldName)))
                .findFirst().orElseThrow().fieldId();
    }

    /** An activated copy of a version without one of its fields, written straight to the database. */
    private long insertActivatedCopyWithout(long versionId, String fieldId) throws SQLException {
        String sql = "INSERT INTO template_version (workspace_id, template_id, version_number, source_artifact_id, kind,"
                + " extraction_version_id, pdf_form_extraction_id, status, field_definitions, activated_at)"
                + " SELECT v.workspace_id, v.template_id, v.version_number + 1, v.source_artifact_id, v.kind, v.extraction_version_id,"
                + " v.pdf_form_extraction_id, 'ACTIVATED',"
                + " (SELECT jsonb_agg(f) FROM jsonb_array_elements(v.field_definitions) f WHERE f->>'fieldId' <> ?), now()"
                + " FROM template_version v WHERE v.id = ? RETURNING id";
        try (Connection connection = DB.superuserConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fieldId);
            statement.setLong(2, versionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static JsonNode spotOf(JsonNode layout, String fieldId) {
        for (JsonNode spot : layout.get("pdf").get("spots")) {
            if (spot.get("fieldId").asText().equals(fieldId)) {
                return spot;
            }
        }
        throw new AssertionError("no place for " + fieldId + " in " + layout.get("pdf").get("spots"));
    }

    private byte[] download(Member member, long artifactId) throws Exception {
        return mockMvc.perform(get("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/download")
                        .cookie(member.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private long upload(Member member, byte[] bytes, String filename) throws Exception {
        long artifactId = read(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads").cookie(member.session())
                        .with(csrf()).contentType("application/json").content("{\"filename\":\"" + filename + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/content").cookie(member.session())
                        .with(csrf()).contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk());
        JsonNode completed = read(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/complete")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(completed.get("status").asText()).isEqualTo("READY");
        return artifactId;
    }

    private long countAsOwner(String sql, long id) throws SQLException {
        try (Connection connection = DB.superuserConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String fillableFormPath(Member member, long artifactId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/artifacts/" + artifactId + "/fillable-form";
    }

    private static String templatesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/templates";
    }

    private static String documentsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/documents";
    }

    private static String documentPath(Member member, long documentId) {
        return documentsPath(member) + "/" + documentId;
    }

    private static String deletionsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/deletions";
    }

    private static JsonNode read(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Member signIn(String subject) {
        long userId = userIdentityRepository.recordLogin(ISSUER, subject, null, null).id();
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(userId);
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
        Cookie cookie = new Cookie("SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        return new Member(cookie, workspace.id(), userId);
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private static byte[] publicPdf(String name) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("fixtures/public/pdf"))) {
            current = current.getParent();
        }
        assertThat(current).as("the repository root, from the test's working directory").isNotNull();
        return Files.readAllBytes(current.resolve("fixtures/public/pdf").resolve(name));
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
