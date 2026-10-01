package io.github.vihuynh72.brownie.api.revision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparation;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparationService;
import io.github.vihuynh72.brownie.core.prepare.PreparedPdfSpot;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import org.springframework.test.web.servlet.ResultMatcher;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Correcting the fill spots of an open PDF form end to end, through real
 * HTTP dispatch and a real Postgres, Azurite and ClamAV, with no Word
 * renderer: on the public flat application form made a template from the
 * places found in it, a box is added, moved, given another text size and
 * made to stop export when its text is too long, renamed, and a found spot
 * taken away, each as a new version the document moves to with its values;
 * the exported PDF holds the value where the box now is. A box off the
 * page or over another spot is refused with the reason. A scan with no
 * spots at all gets a box and is filled. In chat, "put the reference number
 * here" and "fill in here" with a place on the page add a box without any
 * model call, quoted words on more than one line are offered as lines, and
 * words that name no place are placed by one bounded call shown the pages'
 * lines.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
@Import(PdfFillSpotIntegrationTest.CountingModelGatewayConfig.class)
class PdfFillSpotIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-pdf-fill-spots";

    /** Empty space on the flat form's page, below the signature line: where the new box starts, and where it is moved to. */
    private static final double[] FIRST_PLACE = {72, 480, 200, 18};
    private static final double[] SECOND_PLACE = {300, 540, 220, 18};

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

    static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    static final AtomicReference<String> LAST_LINES = new AtomicReference<>();

    /**
     * Counts every model call, since a place on a PDF page named by the
     * person needs none, and when asked where a spot goes, chooses the form's
     * title line by the id it was shown for it.
     */
    @TestConfiguration
    static class CountingModelGatewayConfig {
        @Bean
        @Primary
        ModelGateway countingModelGateway() {
            return request -> {
                MODEL_CALLS.incrementAndGet();
                String lines = request.messages().getLast().content();
                LAST_LINES.set(lines);
                Matcher title = Pattern.compile("(P\\d+L\\d+): Volunteer sign-up \\(page 1\\)").matcher(lines);
                String lineId = title.find() ? "\"" + title.group(1) + "\"" : "null";
                return new ModelCompletion.Success(
                        "{\"lineId\":" + lineId + ",\"placement\":\"END_OF_LINE\",\"text\":null,\"label\":\"Team\",\"type\":\"TEXT\"}",
                        new ModelUsage(10, 5));
            };
        }
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

    @Test
    void aBoxIsAddedMovedRestyledAndRenamedAndAFoundSpotTakenAwayAndTheValueIsDrawnWhereTheBoxNowIs() throws Exception {
        Member member = signIn("subject-pdf-boxes");
        long artifactId = upload(member, publicPdf("flat-application.pdf"), "sign-up.pdf");
        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);
        FieldDefinition name = prepared.spots().stream().map(PreparedPdfSpot::definition)
                .filter(field -> "Full name".equals(field.label())).findFirst().orElseThrow();
        long templateId = createTemplate(member, artifactId, "Volunteer sign-up");
        long firstVersionId = bindAndActivate(member, templateId, prepared.spots());
        JsonNode document = createDocument(member, templateId, firstVersionId, Map.of(name.fieldId(), "Ana Lima"));
        long documentId = document.get("id").asLong();
        long revisionId = document.get("currentRevision").get("id").asLong();

        // A box in the empty space below the signature line.
        String addKey = "spots-" + UUID.randomUUID();
        String add = "{\"kind\":\"ADD_BOX\",\"pageNumber\":1,\"box\":" + box(FIRST_PLACE) + ",\"label\":\"Member number\"}";
        JsonNode added = changeSpots(member, documentId, revisionId, firstVersionId, add, addKey);
        long addedVersionId = added.get("templateVersion").get("id").asLong();
        String numberId = added.get("fieldIds").get(0).asText();
        assertOnVersion(added, addedVersionId, firstVersionId);
        assertThat(added.get("templateVersion").get("kind").asText()).isEqualTo("PDF");
        assertThat(added.get("templateVersion").get("sourceArtifactId").asLong()).as("the file is the base's").isEqualTo(artifactId);
        assertThat(added.get("templateVersion").get("pdfFormExtractionId").asLong()).isEqualTo(prepared.formExtractionId());
        JsonNode numberField = fieldOf(added.get("templateVersion"), numberId);
        assertThat(numberField.get("label").asText()).isEqualTo("Member number");
        assertThat(numberField.get("origin").asText()).isEqualTo("ADDED_BY_PERSON");
        assertThat(numberField.get("bindingKind").asText()).isEqualTo("PAGE_BOX");
        assertBox(numberField.get("pageBox"), FIRST_PLACE);
        assertThat(numberField.get("pageBox").get("overflow").asText()).isEqualTo("SHRINK_TO_FIT");
        assertThat(numberField.get("pageBox").get("style").get("font").asText()).isEqualTo("SANS");
        assertThat(valueOf(added, name.fieldId())).isEqualTo("Ana Lima");
        assertThat(added.get("revision").get("editReason").asText()).isEqualTo("Added a fill spot: Member number.");

        // The same request again is answered with what it did the first time.
        JsonNode replayed = changeSpots(member, documentId, revisionId, firstVersionId, add, addKey);
        assertThat(replayed.get("revision").get("id").asLong()).isEqualTo(added.get("revision").get("id").asLong());
        assertThat(replayed.get("templateVersion").get("id").asLong()).isEqualTo(addedVersionId);

        long typedRevisionId = setValue(member, documentId, added.get("revision").get("id").asLong(), numberId, "M-2041");

        // A box off the page, or over another spot, is refused with the reason, and nothing is made.
        long versionsBefore = countAsOwner("SELECT count(*) FROM template_version WHERE template_id = ?", templateId);
        JsonNode offPage = refused(member, documentId, typedRevisionId, addedVersionId,
                "{\"kind\":\"MOVE_BOX\",\"fieldId\":\"" + numberId + "\",\"box\":" + box(new double[] {580, 480, 200, 18}) + "}");
        assertThat(offPage.get("code").asText()).isEqualTo("FILL_SPOT_PLACE_NOT_ALLOWED");
        assertThat(offPage.get("reason").asText()).isEqualTo("OFF_PAGE");
        FieldBindingTarget.PageBox nameBox = (FieldBindingTarget.PageBox) name.binding();
        JsonNode over = refused(member, documentId, typedRevisionId, addedVersionId,
                "{\"kind\":\"ADD_BOX\",\"pageNumber\":1,\"box\":" + box(new double[] {nameBox.x(), nameBox.y(), nameBox.width(), nameBox.height()})
                        + ",\"label\":\"Nickname\"}");
        assertThat(over.get("reason").asText()).isEqualTo("OVERLAPS");
        assertThat(over.get("detail").asText()).isEqualTo("The box for Nickname would cover another fill spot. Move it, or make it smaller.");
        assertThat(countAsOwner("SELECT count(*) FROM template_version WHERE template_id = ?", templateId)).isEqualTo(versionsBefore);

        JsonNode moved = changeSpots(member, documentId, typedRevisionId, addedVersionId,
                "{\"kind\":\"MOVE_BOX\",\"fieldId\":\"" + numberId + "\",\"box\":" + box(SECOND_PLACE) + "}", "spots-" + UUID.randomUUID());
        long movedVersionId = moved.get("templateVersion").get("id").asLong();
        assertOnVersion(moved, movedVersionId, addedVersionId);
        assertBox(fieldOf(moved.get("templateVersion"), numberId).get("pageBox"), SECOND_PLACE);
        assertThat(valueOf(moved, numberId)).isEqualTo("M-2041");

        JsonNode restyled = changeSpots(member, documentId, moved.get("revision").get("id").asLong(), movedVersionId,
                "{\"kind\":\"RESTYLE_BOX\",\"fieldId\":\"" + numberId + "\",\"sizePt\":10,\"overflow\":\"BLOCK\"}", "spots-" + UUID.randomUUID());
        long restyledVersionId = restyled.get("templateVersion").get("id").asLong();
        assertOnVersion(restyled, restyledVersionId, movedVersionId);
        JsonNode restyledBox = fieldOf(restyled.get("templateVersion"), numberId).get("pageBox");
        assertThat(restyledBox.get("overflow").asText()).isEqualTo("BLOCK");
        assertThat(restyledBox.get("style").get("sizePt").asDouble()).isEqualTo(10.0);
        assertBox(restyledBox, SECOND_PLACE);

        JsonNode renamed = changeSpots(member, documentId, restyled.get("revision").get("id").asLong(), restyledVersionId,
                "{\"kind\":\"RENAME\",\"fieldId\":\"" + numberId + "\",\"label\":\"Membership number\"}", "spots-" + UUID.randomUUID());
        long renamedVersionId = renamed.get("templateVersion").get("id").asLong();
        assertOnVersion(renamed, renamedVersionId, restyledVersionId);
        assertThat(fieldOf(renamed.get("templateVersion"), numberId).get("label").asText()).isEqualTo("Membership number");
        assertThat(valueOf(renamed, numberId)).isEqualTo("M-2041");

        JsonNode removed = changeSpots(member, documentId, renamed.get("revision").get("id").asLong(), renamedVersionId,
                "{\"kind\":\"REMOVE\",\"fieldId\":\"" + name.fieldId() + "\"}", "spots-" + UUID.randomUUID());
        long removedVersionId = removed.get("templateVersion").get("id").asLong();
        assertOnVersion(removed, removedVersionId, renamedVersionId);
        assertThat(removed.get("revision").get("fields").has(name.fieldId())).isFalse();
        assertThat(valueOf(removed, numberId)).isEqualTo("M-2041");
        assertThat(removed.get("templateVersion").get("sourceArtifactId").asLong()).isEqualTo(artifactId);
        assertThat(countAsOwner("SELECT count(*) FROM template_version WHERE template_id = ?", templateId)).isEqualTo(6);

        byte[] exported = export(member, documentId, removed.get("revision").get("id").asLong());
        try (PDDocument pdf = Loader.loadPDF(exported)) {
            assertThat(textIn(pdf, SECOND_PLACE)).contains("M-2041");
            assertThat(textIn(pdf, FIRST_PLACE)).doesNotContain("M-2041");
            assertThat(new PDFTextStripper().getText(pdf)).contains("Volunteer sign-up").doesNotContain("Ana Lima");
        }
    }

    @Test
    void aScanWithNoSpotsGetsABoxAndTheExportedPdfHoldsItsValue() throws Exception {
        Member member = signIn("subject-pdf-scan-box");
        long artifactId = upload(member, publicPdf("scanned-note.pdf"), "note.pdf");
        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);
        assertThat(prepared.spots()).isEmpty();
        long templateId = createTemplate(member, artifactId, "Scanned note");
        JsonNode activated = read(mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions").cookie(member.session())
                        .with(csrf()).contentType("application/json").content("{\"expectedVersionNumber\":1,\"allowNoPlaces\":true}"))
                .andExpect(status().isCreated()).andReturn());
        long firstVersionId = activated.get("id").asLong();
        JsonNode document = createDocument(member, templateId, firstVersionId, Map.of());
        long documentId = document.get("id").asLong();

        double[] place = {100, 100, 250, 20};
        JsonNode added = changeSpots(member, documentId, document.get("currentRevision").get("id").asLong(), firstVersionId,
                "{\"kind\":\"ADD_BOX\",\"pageNumber\":1,\"box\":" + box(place) + ",\"label\":\"Reference\"}", "spots-" + UUID.randomUUID());
        long addedVersionId = added.get("templateVersion").get("id").asLong();
        assertOnVersion(added, addedVersionId, firstVersionId);
        String referenceId = added.get("fieldIds").get(0).asText();
        JsonNode reference = fieldOf(added.get("templateVersion"), referenceId);
        // Nothing on a scan can be read, so the box gets the ordinary look.
        assertThat(reference.get("pageBox").get("style").get("font").asText()).isEqualTo("SANS");
        assertThat(reference.get("pageBox").get("style").get("sizePt").asDouble()).isEqualTo(11.0);

        long typedRevisionId = setValue(member, documentId, added.get("revision").get("id").asLong(), referenceId, "REF-77");
        try (PDDocument pdf = Loader.loadPDF(export(member, documentId, typedRevisionId))) {
            assertThat(textIn(pdf, place)).contains("REF-77");
        }
    }

    @Test
    void chatAddsABoxWhereThePageSaysWithoutAModelCallOffersLinesForWordsOnSeveralAndLetsOneCallChooseALine() throws Exception {
        Member member = signIn("subject-pdf-chat");
        long artifactId = upload(member, publicPdf("flat-application.pdf"), "sign-up.pdf");
        PdfFormPreparation prepared = preparationService.prepare(member.workspaceId(), member.userId(), artifactId);
        long templateId = createTemplate(member, artifactId, "Volunteer sign-up");
        long firstVersionId = bindAndActivate(member, templateId, prepared.spots());
        JsonNode document = createDocument(member, templateId, firstVersionId, Map.of());
        long documentId = document.get("id").asLong();
        long revisionId = document.get("currentRevision").get("id").asLong();
        int callsBefore = MODEL_CALLS.get();

        // A point on the page: the box is the one the page would suggest there.
        String point = "{\"kind\":\"PDF\",\"pageNumber\":1,\"point\":{\"x\":200,\"y\":600}}";
        JsonNode interpreted = assist(member, documentId, "interpret", "put the reference number here", point, null);
        assertThat(interpreted.get("kind").asText()).isEqualTo("ADD_FILL_SPOT");
        assertThat(interpreted.get("executable").asBoolean()).isTrue();
        assertThat(interpreted.get("usesModel").asBoolean()).isFalse();
        assertThat(interpreted.get("summary").asText()).startsWith("Add a fill spot for Reference number at the place you selected.");
        JsonNode suggestion = read(mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions/" + firstVersionId
                        + "/box-suggestion").cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"pageNumber\":1,\"point\":{\"x\":200,\"y\":600}}"))
                .andExpect(status().isOk()).andReturn());

        JsonNode executed = assist(member, documentId, "execute", "put the reference number here", point, revisionId);
        JsonNode change = executed.get("spotChange");
        assertThat(change.get("label").asText()).isEqualTo("Reference number");
        assertThat(change.get("previousRevisionId").asLong()).isEqualTo(revisionId);
        assertThat(change.get("lineText").isNull()).isTrue();
        long pointVersionId = change.get("templateVersionId").asLong();
        JsonNode pointVersion = read(mockMvc.perform(get(templatesPath(member) + "/" + templateId + "/versions/" + pointVersionId)
                .cookie(member.session())).andExpect(status().isOk()).andReturn());
        JsonNode referenceBox = fieldOf(pointVersion, change.get("fieldId").asText()).get("pageBox");
        for (String part : List.of("x", "y", "width", "height")) {
            assertThat(referenceBox.get(part).asDouble()).as(part).isEqualTo(suggestion.get("box").get(part).asDouble());
        }
        assertThat(fieldOf(pointVersion, change.get("fieldId").asText()).get("origin").asText()).isEqualTo("ADDED_BY_PERSON");

        // A line chosen on the page: the box goes beside it, and the answer names the line.
        JsonNode layout = read(mockMvc.perform(get(templatesPath(member) + "/" + templateId + "/versions/" + pointVersionId + "/layout")
                .cookie(member.session())).andExpect(status().isOk()).andReturn());
        JsonNode noteLine = null;
        for (JsonNode line : layout.get("pdf").get("pages").get(0).get("lines")) {
            if (line.get("text").asText().startsWith("Note:")) {
                noteLine = line;
            }
        }
        assertThat(noteLine).isNotNull();
        String lineAnchor = "{\"kind\":\"PDF\",\"pageNumber\":1,\"lineIndex\":" + noteLine.get("index").asInt() + "}";
        long currentRevisionId = currentRevisionId(member, documentId);
        JsonNode byLine = assist(member, documentId, "execute", "fill in here for Notes", lineAnchor, currentRevisionId);
        assertThat(byLine.get("spotChange").get("label").asText()).isEqualTo("Notes");
        assertThat(byLine.get("spotChange").get("lineText").asText()).isEqualTo(noteLine.get("text").asText());
        assertThat(byLine.get("summary").asText())
                .startsWith("Added a fill spot for Notes beside the line \"" + noteLine.get("text").asText() + "\".");

        // Words on two lines are offered as those lines, in the shape the page sends a chosen one back in.
        JsonNode choices = assist(member, documentId, "interpret", "add a fill spot for Desk after \"the\"", null, null);
        assertThat(choices.get("executable").asBoolean()).isFalse();
        assertThat(choices.get("choices")).hasSize(2);
        JsonNode chosen = choices.get("choices").get(1).get("anchor");
        assertThat(chosen.get("kind").asText()).isEqualTo("PDF");
        assertThat(chosen.get("pageNumber").asInt()).isEqualTo(1);
        JsonNode picked = assist(member, documentId, "interpret", "add a fill spot for Desk after \"the\"", JSON.writeValueAsString(chosen), null);
        assertThat(picked.get("executable").asBoolean()).isTrue();
        assertThat(picked.get("summary").asText()).contains(choices.get("choices").get(1).get("lineText").asText());

        // With no place selected on the page, or a place in a Word form's text, the person is asked to select one.
        String wordPlace = "{\"part\":\"MAIN_DOCUMENT\",\"paragraphNodeId\":\"p0\",\"placement\":\"AT\",\"start\":0,\"end\":0,"
                + "\"anchorTextHash\":\"0000000000000000\",\"parserVersion\":\"brownie-docx-graph-v3+poi-5.5.1\"}";
        for (String anchor : Arrays.asList(null, wordPlace)) {
            JsonNode unselected = assist(member, documentId, "interpret", "fill in here for Notes", anchor, null);
            assertThat(unselected.get("executable").asBoolean()).isFalse();
            assertThat(unselected.get("summary").asText()).isEqualTo("Select the place on the page first, then ask again.");
        }
        assertThat(MODEL_CALLS.get()).as("no model call").isEqualTo(callsBefore);

        // Words that name no place are placed by one bounded call, which is shown the pages' lines by page and line.
        JsonNode byModel = assist(member, documentId, "interpret", "add a fill spot for Team next to the title", null, null);
        assertThat(byModel.get("executable").asBoolean()).isTrue();
        assertThat(byModel.get("usesModel").asBoolean()).isTrue();
        JsonNode placed = assist(member, documentId, "execute", "add a fill spot for Team next to the title", null,
                currentRevisionId(member, documentId));
        assertThat(MODEL_CALLS.get()).isEqualTo(callsBefore + 1);
        assertThat(LAST_LINES.get()).contains("P1L0: Volunteer sign-up (page 1)");
        assertThat(placed.get("spotChange").get("lineText").asText()).isEqualTo("Volunteer sign-up");
        JsonNode teamVersion = read(mockMvc.perform(get(templatesPath(member) + "/" + templateId + "/versions/"
                        + placed.get("spotChange").get("templateVersionId").asLong()).cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        JsonNode team = fieldOf(teamVersion, placed.get("spotChange").get("fieldId").asText());
        assertThat(team.get("label").asText()).isEqualTo("Team");
        assertThat(team.get("origin").asText()).as("placed by the model").isEqualTo("FOUND_BY_BROWNIE");
    }

    // ---- steps ----

    private record Member(Cookie session, long workspaceId, long userId) {
    }

    private JsonNode changeSpots(Member member, long documentId, long revisionId, long versionId, String change, String key) throws Exception {
        return read(mockMvc.perform(post(documentPath(member, documentId) + "/fill-spots").cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"templateVersionId\":" + versionId + ",\"changes\":[" + change + "]}"))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode refused(Member member, long documentId, long revisionId, long versionId, String change) throws Exception {
        return read(mockMvc.perform(post(documentPath(member, documentId) + "/fill-spots").cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "spots-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"templateVersionId\":" + versionId + ",\"changes\":[" + change + "]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn());
    }

    /** {@code pageAnchor} is raw JSON or null; {@code expectedRevisionId} is only sent to execute. */
    private JsonNode assist(Member member, long documentId, String step, String text, String pageAnchor, Long expectedRevisionId)
            throws Exception {
        ObjectNode body = JSON.createObjectNode().put("text", text);
        if (pageAnchor != null) {
            body.set("pageAnchor", JSON.readTree(pageAnchor));
        }
        if (expectedRevisionId != null) {
            body.put("expectedRevisionId", expectedRevisionId);
        }
        return read(mockMvc.perform(post(documentPath(member, documentId) + "/assist/" + step).cookie(member.session()).with(csrf())
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn());
    }

    /** The document moved to the new version, made from the one before. */
    private static void assertOnVersion(JsonNode result, long versionId, long previousVersionId) {
        assertThat(versionId).isNotEqualTo(previousVersionId);
        assertThat(result.get("revision").get("templateVersionId").asLong()).isEqualTo(versionId);
        assertThat(result.get("document").get("templateVersionId").asLong()).isEqualTo(versionId);
        assertThat(result.get("templateVersion").get("derivedFromVersionId").asLong()).isEqualTo(previousVersionId);
        assertThat(result.get("templateVersion").get("status").asText()).isEqualTo("ACTIVATED");
    }

    private static void assertBox(JsonNode pageBox, double[] place) {
        assertThat(pageBox.get("page").asInt()).isEqualTo(1);
        assertThat(new double[] {pageBox.get("x").asDouble(), pageBox.get("y").asDouble(), pageBox.get("width").asDouble(),
                pageBox.get("height").asDouble()}).containsExactly(place);
    }

    private static JsonNode fieldOf(JsonNode version, String fieldId) {
        for (JsonNode field : version.get("fields")) {
            if (field.get("fieldId").asText().equals(fieldId)) {
                return field;
            }
        }
        throw new AssertionError("no field " + fieldId + " in " + version.get("fields"));
    }

    private static String valueOf(JsonNode result, String fieldId) {
        return result.get("revision").get("fields").get(fieldId).get("value").asText();
    }

    private static String box(double[] place) {
        return "{\"x\":" + place[0] + ",\"y\":" + place[1] + ",\"width\":" + place[2] + ",\"height\":" + place[3] + "}";
    }

    /** The text drawn inside a box, a point either side of it; the page is not turned and its visible area starts at its corner. */
    private static String textIn(PDDocument pdf, double[] place) throws IOException {
        PDFTextStripperByArea stripper = new PDFTextStripperByArea();
        stripper.addRegion("box", new Rectangle2D.Double(place[0] - 1, place[1] - 1, place[2] + 2, place[3] + 2));
        stripper.extractRegions(pdf.getPage(0));
        return stripper.getTextForRegion("box");
    }

    private long setValue(Member member, long documentId, long revisionId, String fieldId, String value) throws Exception {
        String body = "{\"expectedRevisionId\":" + revisionId + ",\"editReason\":\"typed\",\"edits\":[{\"operation\":\"SET\",\"fieldId\":\""
                + fieldId + "\",\"value\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"" + value + "\"}}]}";
        return read(mockMvc.perform(patch(documentPath(member, documentId) + "/content").cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "edit-" + UUID.randomUUID()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn()).get("id").asLong();
    }

    /** Compiles, validates, approves a PDF export of and exports {@code revisionId}, and gives back the exported PDF. */
    private byte[] export(Member member, long documentId, long revisionId) throws Exception {
        mockMvc.perform(post(documentPath(member, documentId) + "/revisions/" + revisionId + "/compile").cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated());
        JsonNode manifest = read(mockMvc.perform(post(documentPath(member, documentId) + "/validate").cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "validate-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(manifest.get("hasUnresolvedBlocking").asBoolean()).as(manifest.get("findings").toString()).isFalse();
        mockMvc.perform(post(documentPath(member, documentId) + "/export-approval").cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"PDF\"}"))
                .andExpect(status().isCreated());
        JsonNode receipt = read(mockMvc.perform(post(documentPath(member, documentId) + "/export").cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        return mockMvc.perform(get("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + receipt.get("pdfArtifactId").asLong()
                        + "/download").cookie(member.session()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private long createTemplate(Member member, long artifactId, String name) throws Exception {
        return read(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"displayName\":\"" + name + "\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("template").get("id").asLong();
    }

    /** Binds exactly the places the preparation found, as a page making a template from them would, and activates the draft. */
    private long bindAndActivate(Member member, long templateId, List<PreparedPdfSpot> spots) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("expectedVersionNumber", 1);
        ArrayNode fields = body.putArray("fields");
        spots.forEach(spot -> fields.add(fieldJson(spot.definition())));
        mockMvc.perform(put(templatesPath(member) + "/" + templateId + "/draft/bindings").cookie(member.session()).with(csrf())
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk());
        return read(mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"expectedVersionNumber\":2}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    /** A box field as the template API's request takes it; the flat form's places are all boxes. */
    private static ObjectNode fieldJson(FieldDefinition field) {
        ObjectNode json = JSON.createObjectNode()
                .put("fieldId", field.fieldId())
                .put("type", field.type().name())
                .put("cardinality", field.cardinality().name())
                .put("requiredness", field.requiredness().name())
                .put("label", field.label())
                .put("origin", field.effectiveOrigin().name());
        FieldBindingTarget.PageBox box = (FieldBindingTarget.PageBox) field.binding();
        ObjectNode pageBox = json.putObject("binding").put("kind", "PAGE_BOX").putObject("pageBox")
                .put("page", box.page()).put("x", box.x()).put("y", box.y()).put("width", box.width()).put("height", box.height())
                .put("multiline", box.multiline()).put("overflow", box.overflow().name());
        pageBox.putObject("style").put("font", box.style().family().name()).put("bold", box.style().bold()).put("sizePt", box.style().sizePt());
        return json;
    }

    /** Creates a document on {@code versionId} with text {@code values} by field id. */
    private JsonNode createDocument(Member member, long templateId, long versionId, Map<String, String> values) throws Exception {
        ObjectNode create = JSON.createObjectNode()
                .put("title", "Filled form")
                .put("templateId", templateId)
                .put("templateVersionId", versionId)
                .put("initialRevisionReason", "initial draft");
        ObjectNode fields = create.putObject("fields");
        values.forEach((fieldId, value) -> fields.putObject(fieldId).put("type", "TEXT").put("cardinality", "SCALAR").put("value", value));
        return read(mockMvc.perform(post(documentsPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "create-" + UUID.randomUUID()).contentType("application/json")
                        .content(JSON.writeValueAsString(create)))
                .andExpect(status().isCreated()).andReturn());
    }

    private long currentRevisionId(Member member, long documentId) throws Exception {
        return read(mockMvc.perform(get(documentPath(member, documentId)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn()).get("currentRevision").get("id").asLong();
    }

    private long upload(Member member, byte[] bytes, String filename) throws Exception {
        long artifactId = read(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads").cookie(member.session())
                        .with(csrf()).contentType("application/json").content("{\"filename\":\"" + filename + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/content").cookie(member.session())
                        .with(csrf()).contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk());
        ResultMatcher ready = result -> assertThat(read(result).get("status").asText()).isEqualTo("READY");
        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/complete")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andExpect(ready);
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

    private static String templatesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/templates";
    }

    private static String documentsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/documents";
    }

    private static String documentPath(Member member, long documentId) {
        return documentsPath(member) + "/" + documentId;
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
}
