package io.github.vihuynh72.brownie.api.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.batch.BatchNotFoundException;
import io.github.vihuynh72.brownie.core.batch.BatchRepository;
import io.github.vihuynh72.brownie.core.batch.BatchStepKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelUsage;

import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.DocumentContentHasher;
import io.github.vihuynh72.brownie.core.revision.DocumentMutationResult;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The batch routes end to end on a real database: a table upload becomes a
 * batch, the fill step turns its rows into imported documents one at a
 * time, and the leases, pins and deletion routines around that behave the
 * way the schema promises -- proven as the roles involved rather than
 * through mocks. The model gateway is a counting fake so that "no model
 * call" is an observed zero, not an assumption.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
@Import(BatchIntegrationTest.CountingModelGatewayConfig.class)
class BatchIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";
    private static final String ISSUER = "https://issuer-batch";

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

    @TestConfiguration
    static class CountingModelGatewayConfig {
        @Bean
        @Primary
        ModelGateway countingModelGateway() {
            return request -> {
                MODEL_CALLS.incrementAndGet();
                return new ModelCompletion.Success("{\"value\":\"unused\"}", new ModelUsage(10, 10));
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
    private RevisionService revisionService;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private BatchRepository batchRepository;

    @Autowired
    private DataSource apiDataSource;

    private record Member(Cookie session, long workspaceId, long userId) {}

    /** The certificate template: its five spots bound and activated, each form field's name resolved to its field id. */
    private record Certificate(long artifactId, long templateId, long versionId, Map<String, String> fieldIds) {

        String fieldId(String formField) {
            return fieldIds.get(formField);
        }
    }

    /**
     * A small table and the certificate template filled from it: ISO dates
     * fill the date field, a blank row is skipped whole, a text that is not
     * a date lands as a NOT_A_DATE problem, and every made field is recorded
     * as imported and without evidence.
     */
    @Test
    void aCsvTableFillsOneImportedDocumentPerRow() throws Exception {
        Member member = signIn("subject-batch-csv");
        Certificate certificate = certificate(member);
        String csv = "Member name,Award,Date awarded,Club,Citation notes\n"
                + "Ana Lima,Star Contributor,2025-03-04,North Chapter,For the newsletter drive\n"
                + "Ben Okafor,Service Pin,not-a-date,Harbour Circle,For the winter fair\n"
                + ",,,\n"
                + "Cy Rao,Service Pin,2025-06-07,Harbour Circle,For the bake sale\n";
        long tableId = upload(member, csv.getBytes(StandardCharsets.UTF_8), "members.csv");

        JsonNode reading = readJson(mockMvc.perform(post(tablePath(member, tableId)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(reading.get("format").asText()).isEqualTo("CSV");
        assertThat(reading.get("rowCount").asInt()).isEqualTo(3);
        assertThat(reading.get("blankRowCount").asInt()).isEqualTo(1);
        // One unparseable date in the column makes its inferred type TEXT; the cells still carry their parsed dates.
        assertThat(reading.get("columns").get(2).get("inferredType").asText()).isEqualTo("TEXT");

        JsonNode batch = createBatch(member, tableId, certificate, 0);
        long batchId = batch.get("id").asLong();
        assertThat(batch.get("rowCount").asInt()).isEqualTo(4);
        assertThat(statesOf(batch)).containsExactly("PENDING", "PENDING", "SKIPPED_BLANK", "PENDING");

        JsonNode step = fill(member, batchId);
        assertThat(step.get("processed").asInt()).isEqualTo(3);
        assertThat(step.get("remaining").asInt()).isEqualTo(0);

        JsonNode detail = readJson(mockMvc.perform(get(batchPath(member, batchId)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        Map<Integer, JsonNode> rows = rowsByNumber(detail);
        JsonNode ana = rows.get(2);
        long anaDocumentId = ana.get("documentId").asLong();
        assertThat(ana.get("state").asText()).isEqualTo("CREATED");
        assertThat(rows.get(3).get("state").asText()).isEqualTo("CREATED");
        assertThat(rows.get(3).get("problems").get(0).get("code").asText()).isEqualTo("NOT_A_DATE");
        assertThat(rows.get(4).get("state").asText()).isEqualTo("SKIPPED_BLANK");
        assertThat(rows.get(5).get("state").asText()).isEqualTo("CREATED");

        JsonNode document = readJson(mockMvc.perform(get(documentPath(member, anaDocumentId)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(document.get("title").asText()).isEqualTo("Participation certificate, Ana Lima");
        assertThat(document.get("currentRevision").get("editReason").asText())
                .isEqualTo("Filled from row 2 of members.csv");
        assertThat(document.get("currentRevision").get("fields").get(certificate.fieldId("member.name")).get("value").asText())
                .isEqualTo("Ana Lima");
        assertThat(document.get("currentRevision").get("fields").get(certificate.fieldId("award.date")).get("value").asText())
                .isEqualTo("2025-03-04");

        try (Connection connection = DB.superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT authorship, evidence_support FROM document_revision_field_state WHERE document_id = ? ORDER BY field_id")) {
            statement.setLong(1, anaDocumentId);
            try (ResultSet rs = statement.executeQuery()) {
                int rows_seen = 0;
                while (rs.next()) {
                    assertThat(rs.getString(1)).isEqualTo("IMPORTED");
                    assertThat(rs.getString(2)).isEqualTo("MISSING");
                    rows_seen++;
                }
                assertThat(rows_seen).isEqualTo(certificate.fieldIds().size());
            }
        }
        assertThat(text("SELECT edit_reason FROM document_revision WHERE id = ?", ana.get("initialRevisionId").asLong()))
                .isEqualTo("Filled from row 2 of members.csv");
    }

    /** The committed workbook reads and fills exactly like the CSV, through the spreadsheet path. */
    @Test
    void anXlsxTableFillsOneImportedDocumentPerRow() throws Exception {
        Member member = signIn("subject-batch-xlsx");
        Certificate certificate = certificate(member);
        long tableId = upload(member, publicFixture("tables/members-200.xlsx"), "members.xlsx");
        assertThat(text("SELECT detected_media_type FROM artifact WHERE id = ?", tableId)).isEqualTo("XLSX");

        JsonNode reading = readJson(mockMvc.perform(post(tablePath(member, tableId)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(reading.get("format").asText()).isEqualTo("XLSX");
        assertThat(reading.get("rowCount").asInt()).isEqualTo(200);

        JsonNode batch = createBatch(member, tableId, certificate, 0);
        long batchId = batch.get("id").asLong();
        JsonNode step = fill(member, batchId);
        assertThat(step.get("processed").asInt()).isEqualTo(100);
        step = fill(member, batchId);
        assertThat(step.get("processed").asInt()).isEqualTo(100);
        assertThat(step.get("remaining").asInt()).isEqualTo(0);
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ? AND state = 'CREATED'", batchId)).isEqualTo(200);
    }

    /**
     * The 200-row fixture fills completely in bounded steps, and not one
     * model call or usage ledger row is made: batch fill is the reader and
     * the revision service, nothing more. The elapsed seconds are printed
     * for the report.
     */
    @Test
    void theTwoHundredRowBatchFillsWithNoModelCalls() throws Exception {
        Member member = signIn("subject-batch-200");
        Certificate certificate = certificate(member);
        long tableId = upload(member, publicFixture("tables/members-200.csv"), "members.csv");
        JsonNode batch = createBatch(member, tableId, certificate, 0);
        long batchId = batch.get("id").asLong();

        int callsBefore = MODEL_CALLS.get();
        long started = System.nanoTime();
        int remaining = Integer.MAX_VALUE;
        int requests = 0;
        while (remaining > 0) {
            JsonNode step = fill(member, batchId);
            remaining = step.get("remaining").asInt();
            requests++;
            assertThat(requests).as("fill loop bounded").isLessThanOrEqualTo(20);
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("200-row batch fill: " + elapsedMillis + " ms in " + requests + " step requests");

        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ? AND state = 'CREATED'", batchId)).isEqualTo(200);
        assertThat(MODEL_CALLS.get()).as("model calls during the whole fill").isEqualTo(callsBefore);
        assertThat(count("SELECT count(*) FROM model_usage WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    /** The same key answers the same batch; the same key on a different request is refused. */
    @Test
    void createIsIdempotentAndAChangedBodyUnderTheSameKeyConflicts() throws Exception {
        Member member = signIn("subject-batch-idem");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        String key = "batch-key-" + UUID.randomUUID();

        JsonNode first = createBatch(member, tableId, certificate, 0, key);
        JsonNode again = createBatch(member, tableId, certificate, 0, key);
        assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(count("SELECT count(*) FROM batch WHERE workspace_id = ?", member.workspaceId())).isEqualTo(1);

        JsonNode conflict = readJson(mockMvc.perform(post(batchesPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(batchBody(tableId, certificate, 3)))
                .andExpect(status().isConflict()).andReturn());
        assertThat(conflict.get("code").asText()).isEqualTo("BATCH_IDEMPOTENCY_CONFLICT");
    }

    /**
     * A request that died between making a row's document and marking the
     * row is healed by the row's deterministic key and hash: seeding row 2's
     * document exactly as the step would, then filling, links the row to
     * that document instead of making another.
     */
    @Test
    void aPreCreatedRowDocumentIsResumedNotDuplicated() throws Exception {
        Member member = signIn("subject-batch-resume");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        JsonNode batch = createBatch(member, tableId, certificate, 0);
        long batchId = batch.get("id").asLong();

        // Row 2 is the first data row: Ana Lima, Star Contributor, 2025-03-04, North Chapter, the drive.
        Map<String, FieldValue> values = new LinkedHashMap<>();
        values.put(certificate.fieldId("member.name"), new FieldValue.TextValue("Ana Lima"));
        values.put(certificate.fieldId("award.title"), new FieldValue.TextValue("Star Contributor"));
        values.put(certificate.fieldId("award.date"), new FieldValue.DateValue(LocalDate.of(2025, 3, 4)));
        values.put(certificate.fieldId("club.name"), new FieldValue.TextValue("North Chapter"));
        values.put(certificate.fieldId("citation"), new FieldValue.TextValue("For the newsletter drive"));
        DocumentContent content = new DocumentContent(values);
        String title = "Participation certificate, Ana Lima";
        DocumentMutationResult seeded = revisionService.createImportedDocument(
                member.workspaceId(),
                member.userId(),
                new IdempotencyKey("batch-" + batchId + "-row-2-create"),
                CanonicalRequestHash.sha256OfCanonicalText(
                        "FILL\0" + batchId + "\0" + 2 + "\0" + certificate.versionId() + "\0"
                                + DocumentContentHasher.sha256Hex(content) + "\0" + title),
                title,
                certificate.templateId(),
                certificate.versionId(),
                content,
                "Filled from row 2 of members.csv");
        long seededDocumentId = seeded.document().id();

        fill(member, batchId);
        JsonNode detail = readJson(mockMvc.perform(get(batchPath(member, batchId)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(rowsByNumber(detail).get(2).get("documentId").asLong()).isEqualTo(seededDocumentId);
        assertThat(count("SELECT count(*) FROM document WHERE workspace_id = ?", member.workspaceId()))
                .as("one document per data row, the seeded one among them")
                .isEqualTo(2);
    }

    /**
     * A live step lease anywhere in the workspace refuses a step on every
     * batch in it: the lease check is workspace-wide, so a stuck step
     * serializes fills rather than racing them.
     */
    @Test
    void aLiveLeaseOnAnyBatchInTheWorkspaceRefusesAStep() throws Exception {
        Member member = signIn("subject-batch-busy");
        Certificate certificate = certificate(member);
        long firstBatch = createBatch(member, upload(member, smallCsv(), "a.csv"), certificate, 0).get("id").asLong();
        long secondBatch = createBatch(member, upload(member, smallCsv(), "b.csv"), certificate, 0).get("id").asLong();

        try (Connection connection = DB.superuserConnection();
                PreparedStatement lease = connection.prepareStatement(
                        "UPDATE batch SET step_lease_token = gen_random_uuid(), step_kind = 'FILL',"
                                + " step_lease_until = now() + interval '10 minutes' WHERE id = ?")) {
            lease.setLong(1, firstBatch);
            assertThat(lease.executeUpdate()).isEqualTo(1);
        }

        for (long batchId : List.of(firstBatch, secondBatch)) {
            JsonNode refused = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/fill").cookie(member.session()).with(csrf()))
                    .andExpect(status().isConflict()).andReturn());
            assertThat(refused.get("code").asText()).isEqualTo("BATCH_BUSY");
        }
        assertThat(count("SELECT count(*) FROM batch_row WHERE state = 'CREATED' AND batch_id IN (?, ?)", firstBatch, secondBatch))
                .isZero();
    }

    /** A delete asked for while a step holds its lease is refused, and the batch's rows are all still there. */
    @Test
    void aLiveLeaseRefusesADeleteAndTheBatchWaitsForTheStep() throws Exception {
        Member member = signIn("subject-batch-delete-busy");
        Certificate certificate = certificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "a.csv"), certificate, 0).get("id").asLong();

        try (Connection connection = DB.superuserConnection();
                PreparedStatement lease = connection.prepareStatement(
                        "UPDATE batch SET step_lease_token = gen_random_uuid(), step_kind = 'FILL',"
                                + " step_lease_until = now() + interval '10 minutes' WHERE id = ?")) {
            lease.setLong(1, batchId);
            assertThat(lease.executeUpdate()).isEqualTo(1);
        }

        JsonNode refused = readJson(mockMvc.perform(delete(batchPath(member, batchId))
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("BATCH_BUSY");
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ?", batchId)).isEqualTo(2);

        // Once the lease is gone the same delete goes through.
        try (Connection connection = DB.superuserConnection();
                PreparedStatement release = connection.prepareStatement(
                        "UPDATE batch SET step_lease_token = NULL, step_kind = NULL, step_lease_until = NULL WHERE id = ?")) {
            release.setLong(1, batchId);
            release.executeUpdate();
        }
        mockMvc.perform(delete(batchPath(member, batchId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ?", batchId)).isZero();
    }

    /**
     * A row that is no longer PENDING is not marked again: a second fill of
     * it reports nothing written, and the row it belongs to keeps what it
     * already held. A lease asked for a batch that is not there is refused
     * rather than handed out for nothing.
     */
    @Test
    void aRowAlreadyMovedOnIsNotMarkedAgainAndAMissingBatchHandsOutNoLease() throws Exception {
        Member member = signIn("subject-batch-marks");
        Certificate certificate = certificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "a.csv"), certificate, 0).get("id").asLong();
        fill(member, batchId);

        assertThat(batchRepository.markRowCreated(member.workspaceId(), member.userId(), batchId, 2, 0L, 0L, List.of()))
                .isFalse();
        assertThat(batchRepository.markRowFailed(member.workspaceId(), member.userId(), batchId, 2, List.of()))
                .isFalse();
        // The row's own document link survived the refused marks.
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ? AND row_number = 2"
                + " AND state = 'CREATED' AND document_id IS NOT NULL", batchId)).isEqualTo(1);

        assertThatThrownBy(() -> batchRepository.acquireStepLease(
                        member.workspaceId(), member.userId(), 987654321L,
                        BatchStepKind.FILL, java.time.Duration.ofSeconds(60)))
                .isInstanceOf(BatchNotFoundException.class);
    }

    /** Trashing the template mid-fill refuses the next step with rows left PENDING; restoring lets it run out. */
    @Test
    void aTrashedTemplateStopsTheFillAndRestoringLetsItContinue() throws Exception {
        Member member = signIn("subject-batch-trash");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();

        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/templates/" + certificate.templateId() + "/trash")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post(batchPath(member, batchId) + "/fill").cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ? AND state = 'PENDING'", batchId)).isEqualTo(2);

        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/templates/" + certificate.templateId() + "/restore")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        JsonNode step = fill(member, batchId);
        assertThat(step.get("remaining").asInt()).isEqualTo(0);
    }

    /** A batch keeps filling from the version it was made on after the template's active version moves on. */
    @Test
    void thePinnedVersionIsUsedAfterTheTemplateMovesOn() throws Exception {
        Member member = signIn("subject-batch-pinned");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();

        // A fill-spot edit on a document derives and activates the next version, moving the template's pin on.
        JsonNode document = readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/documents")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "doc-" + UUID.randomUUID())
                        .content("{\"title\":\"Moved on\",\"templateId\":" + certificate.templateId()
                                + ",\"templateVersionId\":" + certificate.versionId()
                                + ",\"initialRevisionReason\":\"pin test\",\"fields\":{}}"))
                .andExpect(status().isCreated()).andReturn());
        long movedDocumentId = document.get("id").asLong();
        long revisionId = document.get("currentRevision").get("id").asLong();
        JsonNode moved = readJson(mockMvc.perform(post(documentPath(member, movedDocumentId) + "/fill-spots")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "spots-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"templateVersionId\":" + certificate.versionId()
                                + ",\"changes\":[{\"kind\":\"ADD_BOX\",\"pageNumber\":1,"
                                + "\"box\":{\"x\":72,\"y\":100,\"width\":200,\"height\":18},\"label\":\"Extra note\"}]}"))
                .andExpect(status().isOk()).andReturn());
        long movedVersionId = moved.get("templateVersion").get("id").asLong();
        assertThat(movedVersionId).isNotEqualTo(certificate.versionId());
        assertThat(text("SELECT current_active_version_id FROM template WHERE id = ?", certificate.templateId()))
                .isEqualTo(String.valueOf(movedVersionId));

        fill(member, batchId);
        long documentId = longOf("SELECT document_id FROM batch_row WHERE batch_id = ? AND state = 'CREATED' LIMIT 1", batchId);
        assertThat(longOf("SELECT template_version_id FROM document WHERE id = ?", documentId))
                .isEqualTo(certificate.versionId());
    }

    /** Every batch route answers a different workspace's ids as not found, on every verb. */
    @Test
    void anotherWorkspacesIdsAreNotFound() throws Exception {
        Member owner = signIn("subject-batch-owner");
        Member outsider = signIn("subject-batch-outsider");
        Certificate certificate = certificate(owner);
        long tableId = upload(owner, smallCsv(), "members.csv");
        long batchId = createBatch(owner, tableId, certificate, 0).get("id").asLong();

        mockMvc.perform(get(batchPath(outsider, batchId)).cookie(outsider.session())).andExpect(status().isNotFound());
        mockMvc.perform(post(batchPath(outsider, batchId) + "/fill").cookie(outsider.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(batchPath(outsider, batchId)).cookie(outsider.session()).with(csrf()))
                .andExpect(status().isNotFound());
        // The owner's ids inside the outsider's own workspace are just as absent.
        mockMvc.perform(post(batchesPath(outsider)).cookie(outsider.session()).with(csrf())
                        .header("Idempotency-Key", "x-ws-" + UUID.randomUUID()).contentType("application/json")
                        .content(batchBody(tableId, certificate, 0)))
                .andExpect(status().isNotFound());
        assertThat(readJson(mockMvc.perform(get(batchesPath(outsider)).cookie(outsider.session()))
                .andExpect(status().isOk()).andReturn())).isEmpty();
        // A user who is not in the owner's workspace is refused the reads
        // the same way every other read route refuses them.
        mockMvc.perform(get(batchesPath(owner)).cookie(outsider.session())).andExpect(status().isForbidden());
        mockMvc.perform(get(batchPath(owner, batchId)).cookie(outsider.session())).andExpect(status().isForbidden());
        // And the owner's batch is untouched.
        mockMvc.perform(get(batchPath(owner, batchId)).cookie(owner.session())).andExpect(status().isOk());
    }

    /** The mapping refusals: a required field unanswered, a repeated field named, compose asked for where it is not offered. */
    @Test
    void unmappableMappingsAreRefusedWithTheirReason() throws Exception {
        Member member = signIn("subject-batch-mapping");
        Certificate certificate = certificate(member, Map.of("member.name", FieldRequiredness.REQUIRED));
        long tableId = upload(member, smallCsv(), "members.csv");

        // member.name required but not mapped.
        JsonNode unmapped = refusedBatch(member, tableId, certificate.templateId(), certificate.versionId(),
                "[{\"fieldId\":\"" + certificate.fieldId("award.title") + "\",\"column\":1}]", 0);
        assertThat(unmapped.get("code").asText()).isEqualTo("BATCH_REQUIRED_FIELD_UNMAPPED");
        assertThat(unmapped.get("fields").get(0).asText()).isEqualTo(certificate.fieldId("member.name"));

        // A repeated field cannot be filled from a cell; a PDF template cannot even hold one, so this
        // template is a small Word one made at the repository, as the schema tests make theirs.
        ListTemplate listForm = listTemplate(member);
        JsonNode repeated = refusedBatch(member, tableId, listForm.templateId(), listForm.versionId(),
                "[{\"fieldId\":\"" + listForm.scalarFieldId() + "\",\"column\":0},"
                        + "{\"fieldId\":\"" + listForm.listFieldId() + "\",\"column\":4}]", 0);
        assertThat(repeated.get("code").asText()).isEqualTo("BATCH_FIELD_NOT_FILLABLE");

        // Compose is not offered here, so a compose mapping is refused whatever it names.
        JsonNode compose = refusedBatch(member, tableId, certificate.templateId(), certificate.versionId(),
                "[{\"fieldId\":\"" + certificate.fieldId("member.name") + "\",\"column\":0,\"mode\":\"COMPOSE\"}]", 0);
        assertThat(compose.get("code").asText()).isEqualTo("BATCH_COMPOSE_NOT_OFFERED");

        // And the step answers the same refusal for a batch that could be made.
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();
        JsonNode refused = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/compose")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("BATCH_COMPOSE_NOT_OFFERED");
    }

    /** Deleting a batch removes it and its rows and keeps every document the fill made. */
    @Test
    void deletingABatchKeepsTheDocumentsItMade() throws Exception {
        Member member = signIn("subject-batch-delete");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();
        fill(member, batchId);
        long documentId = longOf("SELECT document_id FROM batch_row WHERE batch_id = ? LIMIT 1", batchId);

        mockMvc.perform(delete(batchPath(member, batchId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(batchPath(member, batchId)).cookie(member.session())).andExpect(status().isNotFound());
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ?", batchId)).isZero();
        mockMvc.perform(get(documentPath(member, documentId)).cookie(member.session())).andExpect(status().isOk());
        assertThat(text("SELECT action FROM audit_event WHERE workspace_id = ? AND action = 'BATCH_DELETED'", member.workspaceId()))
                .isEqualTo("BATCH_DELETED");
    }

    /**
     * The unreferenced sweep treats a batch's spreadsheet as referenced for
     * as long as the batch exists, and collects it once the batch is gone.
     */
    @Test
    void theSweepKeepsABatchsSpreadsheetUntilTheBatchIsDeleted() throws Exception {
        Member member = signIn("subject-batch-sweep");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();
        try (Connection connection = DB.superuserConnection();
                PreparedStatement age = connection.prepareStatement(
                        "UPDATE artifact SET created_at = now() - interval '2 days', finalized_at = now() - interval '2 days' WHERE id = ?")) {
            age.setLong(1, tableId);
            age.executeUpdate();
        }

        assertThat(sweep()).as("the batch's spreadsheet is referenced").isZero();
        assertThat(text("SELECT status FROM artifact WHERE id = ?", tableId)).isEqualTo("READY");

        mockMvc.perform(delete(batchPath(member, batchId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(sweep()).as("after the batch, nothing refers to the file").isEqualTo(1);
        assertThat(text("SELECT status || ':' || rejection_reason FROM artifact WHERE id = ?", tableId))
                .isEqualTo("REJECTED:UNREFERENCED_EXPIRED");
    }

    /**
     * Row-level security and the column grants on the real role: another
     * member cannot see or write the owner's batch rows, and the api login
     * cannot move a batch's pinned version.
     */
    @Test
    void anotherWorkspaceCannotReadOrWriteBatchRowsAndTheApiCannotMoveThePin() throws Exception {
        Member owner = signIn("subject-batch-rls-owner");
        Member intruder = signIn("subject-batch-rls-intruder");
        Certificate certificate = certificate(owner);
        long tableId = upload(owner, smallCsv(), "members.csv");
        long batchId = createBatch(owner, tableId, certificate, 0).get("id").asLong();

        try (Connection connection = apiDataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, intruder.userId());
            try (PreparedStatement read = connection.prepareStatement("SELECT count(*) FROM batch WHERE id = ?")) {
                read.setLong(1, batchId);
                try (ResultSet rs = read.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
            try (PreparedStatement readRows = connection.prepareStatement("SELECT count(*) FROM batch_row WHERE batch_id = ?")) {
                readRows.setLong(1, batchId);
                try (ResultSet rs = readRows.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    """
                    INSERT INTO batch_row (workspace_id, batch_id, row_number, state, problems, compose_state)
                    VALUES (?, ?, 99, 'PENDING', '[]'::jsonb, 'NOT_NEEDED')
                    """)) {
                insert.setLong(1, owner.workspaceId());
                insert.setLong(2, batchId);
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
            }
            connection.rollback();
        }

        // The api role may move a step lease, never the pinned template version.
        try (Connection connection = apiDataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, owner.userId());
            try (PreparedStatement move = connection.prepareStatement(
                    "UPDATE batch SET template_version_id = template_version_id WHERE id = " + batchId)) {
                assertThatThrownBy(move::executeUpdate)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
            connection.rollback();
        }
    }

    /** Purging a batch-made document leaves the row as a record: DOCUMENT_DELETED, its links nulled. */
    @Test
    void aDocumentPurgeMarksItsBatchRowDocumentDeleted() throws Exception {
        Member member = signIn("subject-batch-doc-purge");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();
        fill(member, batchId);
        long documentId = longOf("SELECT document_id FROM batch_row WHERE batch_id = ? AND document_id IS NOT NULL LIMIT 1", batchId);
        int rowNumber = (int) count(
                "SELECT row_number FROM batch_row WHERE document_id = ?", documentId);

        long deletionId = readJson(mockMvc.perform(post(deletionsPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(post(deletionsPath(member) + "/" + deletionId + "/purge").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());

        try (Connection connection = DB.superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT state, document_id, initial_revision_id FROM batch_row WHERE batch_id = ? AND row_number = " + rowNumber)) {
            statement.setLong(1, batchId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("DOCUMENT_DELETED");
                assertThat(rs.getObject(2)).isNull();
                assertThat(rs.getObject(3)).isNull();
            }
        }
    }

    /** A workspace purge with a live batch runs clean: the rows and batch go before the documents they refer to. */
    @Test
    void aWorkspacePurgeRemovesTheBatchWithoutAForeignKeyError() throws Exception {
        Member member = signIn("subject-batch-ws-purge");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();
        fill(member, batchId);
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ?", batchId)).isEqualTo(2);

        long deletionId = readJson(mockMvc.perform(post(deletionsPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk()).andReturn()).get("deletionId").asLong();

        assertThat(count("SELECT count(*) FROM batch WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM batch_row WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM document WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM document_revision_field_state WHERE workspace_id = ?", member.workspaceId())).isZero();
        // Zero rows remain anywhere the inventory counts; the files' blobs
        // stay queued in deletion_blob_task for the worker's blob sweep.
        try (Connection connection = DB.superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT remaining_rows FROM retention_remaining(?)")) {
            statement.setLong(1, deletionId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    // --- helpers ---

    /** The certificate template made from the committed PDF: its five spots bound and activated. */
    private Certificate certificate(Member member) throws Exception {
        return certificate(member, Map.of(), Map.of());
    }

    private Certificate certificate(Member member, Map<String, FieldRequiredness> requiredness) throws Exception {
        return certificate(member, requiredness, Map.of());
    }

    /**
     * The certificate's spots come back from the fillable-form route exactly
     * as they were offered and go into the draft verbatim, as a person
     * accepting them all would send them; {@code requiredness} and {@code
     * cardinality} override by form-field name for the mapping-refusal
     * tests.
     */
    private Certificate certificate(
            Member member, Map<String, FieldRequiredness> requiredness, Map<String, FieldCardinality> cardinality)
            throws Exception {
        long artifactId = upload(member, publicFixture("pdf/certificate-of-participation.pdf"), "certificate.pdf");
        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, artifactId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        long templateId = readJson(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Participation certificate\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("template").get("id").asLong();
        ObjectNode body = JSON.createObjectNode().put("expectedVersionNumber", 1);
        ArrayNode fields = body.putArray("fields");
        Map<String, String> fieldIds = new LinkedHashMap<>();
        for (JsonNode spot : form.get("spots")) {
            String formField = spot.get("binding").get("acroFormField").asText();
            fieldIds.put(formField, spot.get("fieldId").asText());
            ObjectNode field = fields.addObject();
            for (String name : List.of("fieldId", "label", "type", "cardinality", "requiredness", "origin", "docxControl", "blankText")) {
                if (spot.hasNonNull(name)) {
                    field.set(name, spot.get(name));
                }
            }
            field.set("binding", spot.get("binding"));
            if (requiredness.containsKey(formField)) {
                field.put("requiredness", requiredness.get(formField).name());
            }
            if (cardinality.containsKey(formField)) {
                field.put("cardinality", cardinality.get(formField).name());
            }
        }
        mockMvc.perform(put(templatesPath(member) + "/" + templateId + "/draft/bindings").cookie(member.session()).with(csrf())
                        .contentType("application/json").content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk());
        MvcResult activation = mockMvc.perform(post(templatesPath(member) + "/" + templateId + "/versions")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersionNumber\":2}"))
                .andReturn();
        assertThat(activation.getResponse().getStatus())
                .as(activation.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        long versionId = readJson(activation).get("id").asLong();
        return new Certificate(artifactId, templateId, versionId, fieldIds);
    }

    private static byte[] smallCsv() {
        return ("Member name,Award,Date awarded,Club,Citation notes\n"
                + "Ana Lima,Star Contributor,2025-03-04,North Chapter,For the newsletter drive\n"
                + "Ben Okafor,Service Pin,2025-04-05,Harbour Circle,For the winter fair\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The full five-column mapping against the certificate's fields, resolved to their field ids. */
    private static String mappingJson(Certificate certificate) {
        StringBuilder json = new StringBuilder("[");
        String[][] formColumns = {
                {"member.name", "0"}, {"award.title", "1"}, {"award.date", "2"}, {"club.name", "3"}, {"citation", "4"}};
        for (String[] formColumn : formColumns) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"fieldId\":\"").append(certificate.fieldId(formColumn[0]))
                    .append("\",\"column\":").append(formColumn[1]).append('}');
        }
        return json.append(']').toString();
    }

    private static String batchBody(long tableId, Certificate certificate, int titleColumn) {
        return "{\"templateId\":" + certificate.templateId() + ",\"templateVersionId\":" + certificate.versionId()
                + ",\"artifactId\":" + tableId + ",\"mapping\":" + mappingJson(certificate) + ",\"titleColumn\":" + titleColumn + "}";
    }

    private JsonNode createBatch(Member member, long tableId, Certificate certificate, int titleColumn) throws Exception {
        return createBatch(member, tableId, certificate, titleColumn, "batch-" + UUID.randomUUID());
    }

    private JsonNode createBatch(Member member, long tableId, Certificate certificate, int titleColumn, String key)
            throws Exception {
        return readJson(mockMvc.perform(post(batchesPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content(batchBody(tableId, certificate, titleColumn)))
                .andExpect(status().isCreated()).andReturn());
    }

    /** A template with a scalar field and a repeated one, made over a Word graph without the baseline render. */
    private record ListTemplate(long templateId, long versionId, String scalarFieldId, String listFieldId) {}

    private ListTemplate listTemplate(Member member) throws SQLException {
        long artifactId = insertReadyArtifact(member.workspaceId(), member.userId());
        StructuralNode scalar = new StructuralNode(
                "p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "member.name", null, List.of());
        StructuralNode list = new StructuralNode(
                "p1/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "notes", null, List.of());
        DocxStructuralGraph graph = new DocxStructuralGraph(
                "batch-mapping-test",
                List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT,
                        new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(scalar, list)))));
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(
                member.workspaceId(), member.userId(), artifactId, "batch-mapping-test", graph);
        Template template = templateRepository.createDraft(
                member.workspaceId(), member.userId(), "List form", artifactId, extraction.id());
        templateRepository.replaceDraftBindings(
                member.workspaceId(),
                member.userId(),
                template.id(),
                1,
                List.of(
                        new FieldDefinition("member.name", FieldType.TEXT, FieldCardinality.SCALAR,
                                FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag("member.name")),
                        new FieldDefinition("notes", FieldType.TEXT, FieldCardinality.REPEATED,
                                FieldRequiredness.OPTIONAL, new FieldBindingTarget.ContentControlTag("notes"))));
        TemplateVersion version = templateRepository.activate(member.workspaceId(), member.userId(), template.id(), 2);
        return new ListTemplate(template.id(), version.id(), "member.name", "notes");
    }

    private long insertReadyArtifact(long workspaceId, long userId) throws SQLException {
        try (Connection connection = apiDataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type)
                    VALUES (?, ?, 'READY', 100, 'DOCX')
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setString(2, "batch-mapping-test-" + UUID.randomUUID());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    long id = rs.getLong(1);
                    connection.commit();
                    return id;
                }
            }
        }
    }

    private JsonNode refusedBatch(
            Member member, long tableId, long templateId, long versionId, String mapping, int titleColumn) throws Exception {
        return readJson(mockMvc.perform(post(batchesPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "batch-ref-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"templateId\":" + templateId + ",\"templateVersionId\":" + versionId
                                + ",\"artifactId\":" + tableId + ",\"mapping\":" + mapping + ",\"titleColumn\":" + titleColumn + "}"))
                .andExpect(status().isUnprocessableEntity()).andReturn());
    }

    /** One fill step that is expected to run, asserted for its shape. */
    private JsonNode fill(Member member, long batchId) throws Exception {
        return readJson(mockMvc.perform(post(batchPath(member, batchId) + "/fill").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
    }

    private static List<String> statesOf(JsonNode batch) {
        List<String> states = new ArrayList<>();
        batch.get("rows").forEach(row -> states.add(row.get("state").asText()));
        return states;
    }

    private static Map<Integer, JsonNode> rowsByNumber(JsonNode detail) {
        Map<Integer, JsonNode> rows = new LinkedHashMap<>();
        detail.get("rows").forEach(row -> rows.put(row.get("rowNumber").asInt(), row));
        return rows;
    }

    /** The worker's removable-payload sweep, run as the worker role itself; returns how many payloads it took. */
    private int sweep() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DB.jdbcUrl(), "brownie_worker", WORKER_PASSWORD);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM worker_collect_removable_payloads(0, 3600000, 64)")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long upload(Member member, byte[] bytes, String filename) throws Exception {
        long artifactId = readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"filename\":\"" + filename + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/content")
                        .cookie(member.session()).with(csrf()).contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk());
        MvcResult completed = mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId
                        + "/complete").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(readJson(completed).get("status").asText()).isEqualTo("READY");
        return artifactId;
    }

    private String text(String sql, long id) throws SQLException {
        try (Connection connection = DB.superuserConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long longOf(String sql, long id) throws SQLException {
        try (Connection connection = DB.superuserConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private long count(String sql, long... ids) throws SQLException {
        try (Connection connection = DB.superuserConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                statement.setLong(i + 1, ids[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            context.setString(1, String.valueOf(userId));
            context.executeQuery();
        }
    }

    private static String batchesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/batches";
    }

    private static String batchPath(Member member, long batchId) {
        return batchesPath(member) + "/" + batchId;
    }

    private static String tablePath(Member member, long artifactId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/artifacts/" + artifactId + "/table";
    }

    private static String fillableFormPath(Member member, long artifactId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/artifacts/" + artifactId + "/fillable-form";
    }

    private static String templatesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/templates";
    }

    private static String documentPath(Member member, long documentId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/documents/" + documentId;
    }

    private static String deletionsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/deletions";
    }

    private static JsonNode readJson(MvcResult result) throws Exception {
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

    private static byte[] publicFixture(String name) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("fixtures/public"))) {
            current = current.getParent();
        }
        assertThat(current).as("the repository root, from the test's working directory").isNotNull();
        return Files.readAllBytes(current.resolve("fixtures/public").resolve(name));
    }
}
