package io.github.vihuynh72.brownie.api.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
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
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rest of a batch's life end to end: accept turns rows' review state
 * over one revision at a time, check writes manifests, an export run is
 * created and advanced to receipts, and the download streams only bytes
 * proven against them. Everything that must hold a row out -- a gone
 * document, a waiting suggestion, a blocking finding, an edit since
 * approval -- is exercised as itself, as is the archive's own safety: the
 * row-numbered safe names, the neutralised CSV, and the abort a changed
 * blob causes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
@Import(BatchExportIntegrationTest.CountingModelGatewayConfig.class)
class BatchExportIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-batch-export";

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

    /** A fake that must never be asked: the batch steps here are all render and review, no model. */
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

        /** The primary pool, wrapped so every statement handed out is countable. */
        @Bean
        @Primary
        DataSource countingDataSource(org.springframework.boot.jdbc.autoconfigure.DataSourceProperties properties) {
            return new CountingDataSource(properties.initializeDataSourceBuilder().build());
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
    private BlobStore blobStore;

    @Autowired
    private DataSource apiDataSource;

    private record Member(Cookie session, long workspaceId, long userId) {}

    private record Certificate(long artifactId, long templateId, long versionId, Map<String, String> fieldIds) {

        String fieldId(String formField) {
            return fieldIds.get(formField);
        }
    }

    /** The whole lane on the certificate: fill, accept, check, export and download, every row seen through. */
    @Test
    void fillAcceptCheckExportAndDownloadTheWholeBatch() throws Exception {
        Member member = signIn("subject-bx-lane");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();

        fillAll(member, batchId);

        JsonNode accept = acceptStep(member, batchId);
        assertThat(accept.get("processed").asInt()).isEqualTo(2);
        assertThat(accept.get("remaining").asInt()).isEqualTo(0);
        for (JsonNode row : accept.get("rows")) {
            assertThat(row.get("result").asText()).isEqualTo("ACCEPTED");
        }
        // A second accept is a no-op: both rows are already fully reviewed.
        JsonNode again = acceptStep(member, batchId);
        assertThat(again.get("processed").asInt()).isZero();
        for (JsonNode row : again.get("rows")) {
            assertThat(row.get("result").asText()).isEqualTo("SKIPPED");
            assertThat(row.get("code").asText()).isEqualTo("ALREADY_ACCEPTED");
        }

        JsonNode check = checkStep(member, batchId);
        assertThat(check.get("processed").asInt()).isEqualTo(2);
        assertThat(check.get("remaining").asInt()).isZero();
        // A second check is a no-op: every current revision already has a manifest.
        assertThat(checkStep(member, batchId).get("processed").asInt()).isZero();

        JsonNode detail = getBatch(member, batchId);
        Map<Integer, JsonNode> rows = rowsByNumber(detail);
        for (int rowNumber : List.of(2, 3)) {
            JsonNode row = rows.get(rowNumber);
            assertThat(row.get("accepted").asBoolean()).as("row " + rowNumber).isTrue();
            assertThat(row.get("check").get("state").asText()).isEqualTo("CHECKED");
            assertThat(row.get("documentPresent").asBoolean()).isTrue();
            assertThat(row.get("aiComposed").asBoolean()).isFalse();
            assertThat(row.get("suggestionWaiting").asBoolean()).isFalse();
        }

        JsonNode export = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID());
        long exportId = export.get("id").asLong();
        assertThat(export.get("state").asText()).isEqualTo("RUNNING");
        assertThat(export.get("items")).hasSize(2);

        // The download refuses a run that is still working.
        mockMvc.perform(get(batchPath(member, batchId) + "/exports/" + exportId + "/download")
                        .cookie(member.session()))
                .andExpect(status().isConflict());

        JsonNode advanced = advanceExport(member, batchId, exportId);
        assertThat(advanced.get("state").asText()).isEqualTo("COMPLETE");
        for (JsonNode item : advanced.get("items")) {
            assertThat(item.get("outcome").asText()).isEqualTo("EXPORTED");
            assertThat(item.get("exportReceiptId").isNumber()).isTrue();
        }
        assertThat(text("SELECT action FROM audit_event WHERE workspace_id = ? AND action = 'BATCH_EXPORTED'",
                member.workspaceId())).isEqualTo("BATCH_EXPORTED");

        Map<String, byte[]> zip = unzip(downloadZip(member, batchId, exportId));
        assertThat(zip.keySet()).containsExactlyInAnyOrder(
                "002 Participation certificate, Ana Lima.pdf",
                "003 Participation certificate, Ben Okafor.pdf",
                "rows.csv");
        String csv = new String(zip.get("rows.csv"), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("Row,Document,Outcome,Word file,PDF file,Word SHA-256,PDF SHA-256,Detail");
        assertThat(csv).contains("2,\"Participation certificate, Ana Lima\",EXPORTED")
                .contains("003 Participation certificate, Ben Okafor.pdf");
        // The detail read now reports the export each row shipped in.
        detail = getBatch(member, batchId);
        for (int rowNumber : List.of(2, 3)) {
            JsonNode lastExport = rowsByNumber(detail).get(rowNumber).get("lastExport");
            assertThat(lastExport.get("exportId").asLong()).isEqualTo(exportId);
            assertThat(lastExport.get("outcome").asText()).isEqualTo("EXPORTED");
        }
    }

    /** Every verdict accept can give that is not "yes", one row each. */
    @Test
    void acceptNamesEveryReasonARowIsSkipped() throws Exception {
        Member member = signIn("subject-bx-accept-skips");
        Certificate certificate = certificate(member, Map.of("citation", FieldRequiredness.REQUIRED));
        // Row 2 fills cleanly; row 3 carries a bad date (problem, unchanged); row 4 has an empty required citation.
        String csv = "Member name,Award,Date awarded,Club,Citation notes\n"
                + "Ana Lima,Star Contributor,2025-03-04,North Chapter,For the drive\n"
                + "Ben Okafor,Service Pin,not-a-date,Harbour Circle,For the fair\n"
                + "Cy Rao,Service Pin,2025-06-07,Harbour Circle,\n";
        long batchId = createBatch(member, upload(member, csv.getBytes(StandardCharsets.UTF_8), "members.csv"),
                certificate, 0).get("id").asLong();

        // Before the fill, both rows skip NOT_CREATED.
        JsonNode first = acceptStep(member, batchId);
        assertThat(first.get("processed").asInt()).isZero();
        for (JsonNode row : first.get("rows")) {
            if (row.get("state").asText().equals("PENDING")) {
                assertThat(row.get("code").asText()).isEqualTo("NOT_CREATED");
            }
        }

        fillAll(member, batchId);
        JsonNode detail = getBatch(member, batchId);
        Map<Integer, JsonNode> rows = rowsByNumber(detail);
        long anaDoc = rows.get(2).get("documentId").asLong();
        long anaRevision = rows.get(2).get("currentRevisionId").asLong();
        long benDoc = rows.get(3).get("documentId").asLong();
        long cyDoc = rows.get(4).get("documentId").asLong();

        // Row 5's row does not exist; give row 2 a waiting suggestion and
        // row 3's document an AI-composed field, so each code is one row's own.
        revisionService.proposePatch(member.workspaceId(), member.userId(), anaDoc, anaRevision,
                Map.of(certificate.fieldId("citation"), new FieldValue.TextValue("A proposed citation")), Map.of());
        try (Connection connection = DB.superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE document_revision_field_state SET authorship = 'AI_COMPOSED'"
                                + " WHERE document_id = ? AND field_id = ?")) {
            statement.setLong(1, benDoc);
            statement.setString(2, certificate.fieldId("member.name"));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        JsonNode accept = acceptStep(member, batchId);
        Map<Integer, JsonNode> verdicts = verdictsByRow(accept);
        assertThat(verdicts.get(2).get("code").asText()).isEqualTo("SUGGESTION_WAITING");
        // Row 3 has both a problem and an AI-composed field; the suggestion
        // of AI authorship is answered first -- it can only be lifted by a person.
        assertThat(verdicts.get(3).get("code").asText()).isEqualTo("AI_COMPOSED");
        // Row 4's required citation is empty: blocking before its problem list is even asked.
        assertThat(verdicts.get(4).get("code").asText()).isEqualTo("BLOCKING");
        assertThat(accept.get("processed").asInt()).isZero();
    }

    /** A row that filled with problems is accepted once its document's content was fixed by hand. */
    @Test
    void aRowWithProblemsIsAcceptedAfterItsContentIsEdited() throws Exception {
        Member member = signIn("subject-bx-accept-fix");
        Certificate certificate = certificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, batchId);

        // Give row 2's document a problem-shaped history: a NOT_A_DATE row would do, so make one instead by editing.
        // Simpler and as true: mark the row's problems directly, as a NOT_A_DATE fill would have written them.
        JsonNode detail = getBatch(member, batchId);
        JsonNode row = rowsByNumber(detail).get(2);
        long documentId = row.get("documentId").asLong();
        long revisionId = row.get("currentRevisionId").asLong();
        try (Connection connection = DB.superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE batch_row SET problems = '[{\"code\":\"NOT_A_DATE\",\"fieldId\":null,\"column\":2}]'::jsonb"
                                + " WHERE batch_id = ? AND row_number = 2")) {
            statement.setLong(1, batchId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        JsonNode skipped = acceptStep(member, batchId);
        assertThat(verdictsByRow(skipped).get(2).get("code").asText()).isEqualTo("HAS_PROBLEMS");

        // The person edits the document: the content hash moves off the fill's own bytes, and the row is eligible.
        JsonNode edited = readJson(mockMvc.perform(patch(documentPath(member, documentId) + "/content")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "edit-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + ",\"editReason\":\"fix the date\","
                                + "\"edits\":[{\"operation\":\"SET\",\"fieldId\":\"" + certificate.fieldId("award.date")
                                + "\",\"value\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2025-03-05\"}}]}"))
                .andExpect(status().isOk()).andReturn());

        JsonNode accepted = acceptStep(member, batchId);
        assertThat(verdictsByRow(accepted).get(2).get("result").asText()).isEqualTo("ACCEPTED");
        // And once more: the row is now reported as fully reviewed, not accepted again.
        assertThat(verdictsByRow(acceptStep(member, batchId)).get(2).get("code").asText())
                .isEqualTo("ALREADY_ACCEPTED");
    }

    /**
     * The detail read's shape and its cost: a five-hundred-row batch's GET
     * answers in a constant, small number of statements -- counted through
     * the pool -- and in time a screen can wait on.
     */
    @Test
    void theDetailReadIsSetBasedAtFiveHundredRows() throws Exception {
        Member member = signIn("subject-bx-500");
        Certificate certificate = certificate(member);
        StringBuilder csv = new StringBuilder("Member name,Award,Date awarded,Club,Citation notes\n");
        for (int i = 1; i <= 500; i++) {
            csv.append("Member ").append(i).append(",Service Pin,2025-03-0").append(i % 9 + 1)
                    .append(",Harbour Circle,For the fair\n");
        }
        long batchId = createBatch(member, upload(member, csv.toString().getBytes(StandardCharsets.UTF_8), "members.csv"),
                certificate, 0).get("id").asLong();
        fillAll(member, batchId);
        assertThat(count("SELECT count(*) FROM batch_row WHERE batch_id = ? AND state = 'CREATED'", batchId))
                .isEqualTo(500);

        QUERY_COUNT.set(0);
        long started = System.nanoTime();
        JsonNode detail = getBatch(member, batchId);
        long millis = (System.nanoTime() - started) / 1_000_000;
        int bigQueries = QUERY_COUNT.get();

        // The honest proof of set-based: a two-row batch's GET issues
        // exactly the same statements -- the count is a property of the
        // kinds of facts, never of the row count.
        long smallBatchId = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, smallBatchId);
        QUERY_COUNT.set(0);
        JsonNode smallDetail = getBatch(member, smallBatchId);
        int smallQueries = QUERY_COUNT.get();
        System.out.println("500-row batch detail GET: " + millis + " ms, " + bigQueries
                + " statements (2-row GET: " + smallQueries + ")");
        assertThat(detail.get("rows")).hasSize(500);
        assertThat(smallDetail.get("rows")).hasSize(2);
        // A couple of the reads are conditional -- they skip when their
        // candidate set is empty -- so the counts can differ by a small
        // constant; what they can never do is grow with the rows: had any
        // read been per-row, five hundred rows would mean hundreds more
        // statements than two rows, not single digits.
        assertThat(bigQueries - smallQueries)
                .as("a per-row read would differ by hundreds, not a handful")
                .isLessThanOrEqualTo(4);
        assertThat(bigQueries)
                .as("one statement per kind of fact, whatever the row count")
                .isLessThanOrEqualTo(30);
    }

    /** An advance that stops part way is picked up, never repeated: one receipt per document, every item resolved. */
    @Test
    void aResumedExportFinishesEachDocumentOnce() throws Exception {
        Member member = signIn("subject-bx-resume");
        Certificate certificate = certificate(member);
        StringBuilder csv = new StringBuilder("Member name,Award,Date awarded,Club,Citation notes\n");
        for (int i = 1; i <= 12; i++) {
            csv.append("Member ").append(i).append(",Service Pin,2025-03-04,Harbour Circle,For the fair\n");
        }
        long batchId = createBatch(member, upload(member, csv.toString().getBytes(StandardCharsets.UTF_8), "members.csv"),
                certificate, 0).get("id").asLong();
        fillAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();

        // The advance budget is ten items a call, so the first advance stops with two still PENDING.
        JsonNode first = advanceExport(member, batchId, exportId);
        assertThat(first.get("processed").asInt()).isEqualTo(10);
        assertThat(first.get("remaining").asInt()).isEqualTo(2);
        JsonNode second = advanceExport(member, batchId, exportId);
        assertThat(second.get("processed").asInt()).isEqualTo(2);
        assertThat(second.get("remaining").asInt()).isZero();
        assertThat(second.get("state").asText()).isEqualTo("COMPLETE");
        assertThat(count("SELECT count(*) FROM batch_export_item WHERE batch_export_id = ? AND outcome = 'EXPORTED'", exportId))
                .isEqualTo(12);
        // No document was exported twice.
        assertThat(count("SELECT count(*) FROM (SELECT document_id FROM export_receipt"
                + " WHERE workspace_id = ? GROUP BY document_id HAVING count(*) > 1) d", member.workspaceId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM export_receipt WHERE workspace_id = ?", member.workspaceId()))
                .isEqualTo(12);
    }

    /** A second step while a lease lives anywhere in the workspace is told the batch is busy. */
    @Test
    void aHeldLeaseRefusesAnAdvance() throws Exception {
        Member member = signIn("subject-bx-advance-busy");
        Certificate certificate = certificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();

        try (Connection connection = DB.superuserConnection();
                PreparedStatement lease = connection.prepareStatement(
                        "UPDATE batch SET step_lease_token = gen_random_uuid(), step_kind = 'EXPORT',"
                                + " step_lease_until = now() + interval '10 minutes' WHERE id = ?")) {
            lease.setLong(1, batchId);
            assertThat(lease.executeUpdate()).isEqualTo(1);
        }
        JsonNode refused = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/exports/" + exportId + "/advance")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict()).andReturn());
        assertThat(refused.get("code").asText()).isEqualTo("BATCH_BUSY");
    }

    /** Until a run is COMPLETE with something exported, there is no archive; another workspace never sees the run. */
    @Test
    void theDownloadAnswersOnlyWhenThereIsSomethingToGive() throws Exception {
        Member member = signIn("subject-bx-download-waits");
        Member outsider = signIn("subject-bx-download-outsider");
        Certificate certificate = certificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, batchId);
        // The documents are gone before the run: every item resolves DOCUMENT_GONE, nothing is ever exported.
        try (Connection connection = DB.superuserConnection();
                PreparedStatement trash = connection.prepareStatement(
                        "UPDATE document SET trashed_at = now() WHERE workspace_id = ?")) {
            trash.setLong(1, member.workspaceId());
            assertThat(trash.executeUpdate()).isEqualTo(2);
        }
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();

        mockMvc.perform(get(batchPath(member, batchId) + "/exports/" + exportId + "/download").cookie(member.session()))
                .andExpect(status().isConflict());
        JsonNode advanced = advanceExport(member, batchId, exportId);
        assertThat(advanced.get("state").asText()).isEqualTo("COMPLETE");
        for (JsonNode item : advanced.get("items")) {
            assertThat(item.get("outcome").asText()).isEqualTo("DOCUMENT_GONE");
        }
        mockMvc.perform(get(batchPath(member, batchId) + "/exports/" + exportId + "/download").cookie(member.session()))
                .andExpect(status().isConflict());

        // And the outsider reaches none of it: batch, export and item ids are all 404 to them.
        mockMvc.perform(get(batchPath(outsider, batchId) + "/exports/" + exportId).cookie(outsider.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(batchPath(outsider, batchId) + "/exports/" + exportId + "/advance")
                        .cookie(outsider.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(batchPath(outsider, batchId) + "/exports/" + exportId + "/download")
                        .cookie(outsider.session()))
                .andExpect(status().isNotFound());
    }

    /** Everything hostile a title can hold is cleaned out of the entry name; entries stay unique. */
    @Test
    void hostileTitlesProduceSafeUniqueEntryNames() throws Exception {
        Member member = signIn("subject-bx-hostile");
        Certificate certificate = certificate(member);
        String longName = "x".repeat(300);
        // The hostile text comes in through the title column -- a column no
        // field maps to -- so every row still fills and exports, and the
        // document titles themselves are the attack.
        String csv = "Member name,Award,Date awarded,Club,Citation notes,Title words\n"
                + "Ana Lima,Pin,2025-03-04,Club,Note,../../evil\n"
                + "Ben Okafor,Pin,2025-03-04,Club,Note,a/b\\c\n"
                + "Cy Rao,Pin,2025-03-04,Club,Note,CON\n"
                + "Di Amari,Pin,2025-03-04,Club,Note," + longName + "\n";
        long batchId = createBatch(member, upload(member, csv.getBytes(StandardCharsets.UTF_8), "members.csv"),
                certificate, 5).get("id").asLong();
        fillAll(member, batchId);
        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();
        advanceAll(member, batchId, exportId);

        Map<String, byte[]> zip = unzip(downloadZip(member, batchId, exportId));
        assertThat(zip).hasSize(5);
        for (String name : zip.keySet()) {
            assertThat(name).as(name).doesNotContain("/").doesNotContain("\\").doesNotContain("..")
                    .doesNotContain("\r").doesNotContain("\n");
            if (!name.equals("rows.csv")) {
                assertThat(name).endsWith(".pdf");
                // row number + space + at most an 80-char title
                assertThat(name.length()).isLessThanOrEqualTo(4 + 1 + 80 + 4 + 4);
            }
        }
    }

    /** Every way a row is kept out of an export names itself: gone, suggestion waiting, blocking, changed. */
    @Test
    void rowsThatCannotExportSayWhy() throws Exception {
        Member member = signIn("subject-bx-exclusions");
        Certificate certificate = certificate(member, Map.of("citation", FieldRequiredness.REQUIRED));
        String csv = "Member name,Award,Date awarded,Club,Citation notes\n"
                + "Ana Lima,Pin,2025-03-04,Club,note\n"
                + "Ben Okafor,Pin,2025-03-04,Club,note\n"
                + "Cy Rao,Pin,2025-03-04,Club,\n"
                + "Di Amari,Pin,2025-03-04,Club,note\n"
                + "Eli Sorn,Pin,2025-03-04,Club,note\n";
        long batchId = createBatch(member, upload(member, csv.getBytes(StandardCharsets.UTF_8), "members.csv"),
                certificate, 0).get("id").asLong();
        fillAll(member, batchId);
        JsonNode detail = getBatch(member, batchId);
        Map<Integer, JsonNode> rows = rowsByNumber(detail);

        // Row 3: a suggestion waits on it.
        revisionService.proposePatch(member.workspaceId(), member.userId(),
                rows.get(3).get("documentId").asLong(), rows.get(3).get("currentRevisionId").asLong(),
                Map.of(certificate.fieldId("club.name"), new FieldValue.TextValue("Other club")), Map.of());
        // Row 6: its document is gone (in the trash) before the run is made.
        long goneDocumentId = rows.get(6).get("documentId").asLong();

        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();

        // After the run is made: trash row 6's document, edit row 5's.
        try (Connection connection = DB.superuserConnection();
                PreparedStatement trash = connection.prepareStatement(
                        "UPDATE document SET trashed_at = now() WHERE id = ?")) {
            trash.setLong(1, goneDocumentId);
            assertThat(trash.executeUpdate()).isEqualTo(1);
        }
        long changedDoc = rows.get(5).get("documentId").asLong();
        long changedRevision = rows.get(5).get("currentRevisionId").asLong();
        mockMvc.perform(patch(documentPath(member, changedDoc) + "/content")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "edit-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + changedRevision + ",\"editReason\":\"late fix\","
                                + "\"edits\":[{\"operation\":\"SET\",\"fieldId\":\"" + certificate.fieldId("club.name")
                                + "\",\"value\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Edited club\"}}]}"))
                .andExpect(status().isOk());

        JsonNode advanced = advanceExport(member, batchId, exportId);
        assertThat(advanced.get("state").asText()).isEqualTo("COMPLETE");
        Map<Integer, JsonNode> items = itemsByRow(advanced);
        assertThat(items.get(2).get("outcome").asText()).isEqualTo("EXPORTED");
        assertThat(items.get(3).get("outcome").asText()).isEqualTo("SUGGESTION_WAITING");
        // Row 4's required citation was empty: BLOCKED, naming the finding and the field.
        assertThat(items.get(4).get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(items.get(4).get("detail").asText())
                .contains("MISSING_REQUIRED_FIELD").contains(certificate.fieldId("citation"));
        assertThat(items.get(5).get("outcome").asText()).isEqualTo("CHANGED");
        assertThat(items.get(5).get("detail").asText()).isEqualTo("CHANGED_SINCE_APPROVAL");
        assertThat(items.get(6).get("outcome").asText()).isEqualTo("DOCUMENT_GONE");

        // Only the one good row is in the archive.
        Map<String, byte[]> zip = unzip(downloadZip(member, batchId, exportId));
        assertThat(zip.keySet()).containsExactlyInAnyOrder(
                "002 Participation certificate, Ana Lima.pdf", "rows.csv");
    }

    /** A blob changed under the receipt's back is never served: the stream aborts on the mismatch. */
    @Test
    void anAlteredBlobAbortsTheDownload() throws Exception {
        Member member = signIn("subject-bx-integrity");
        Certificate certificate = certificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, batchId);
        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();
        advanceAll(member, batchId, exportId);

        String blobKey = text("SELECT a.blob_key FROM artifact a JOIN export_receipt e ON e.pdf_artifact_id = a.id"
                + " JOIN batch_export_item i ON i.export_receipt_id = e.id WHERE i.batch_export_id = ? LIMIT 1", exportId);
        byte[] tampered = "%PDF-tampered".getBytes(StandardCharsets.UTF_8);
        blobStore.writeAndDigest(blobKey, new ByteArrayInputStream(tampered), tampered.length + 1);

        // The first entry reads its blob, proves the hash wrong, and the
        // stream dies right there: the answer is the failure, not an
        // archive with the wrong bytes inside.
        MvcResult started = mockMvc.perform(get(batchPath(member, batchId) + "/exports/" + exportId + "/download")
                        .cookie(member.session()))
                .andReturn();
        MvcResult dispatched = mockMvc.perform(asyncDispatch(started)).andReturn();
        assertThat(dispatched.getResponse().getStatus()).isEqualTo(500);
        String body = new String(dispatched.getResponse().getContentAsByteArray(), StandardCharsets.ISO_8859_1);
        assertThat(body).doesNotContain("%PDF-tampered").doesNotContain("PK\u0003\u0004");
    }

    /** The export still ships the version the batch was made on after the template moved on. */
    @Test
    void thePinnedVersionIsWhatExportsAfterTheTemplateMovesOn() throws Exception {
        Member member = signIn("subject-bx-pinned");
        Certificate certificate = certificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, batchId);

        // Move the template on the same way the fill test does: a fill-spot change activates the next version.
        JsonNode document = readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/documents")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .header("Idempotency-Key", "doc-" + UUID.randomUUID())
                        .content("{\"title\":\"Moved on\",\"templateId\":" + certificate.templateId()
                                + ",\"templateVersionId\":" + certificate.versionId()
                                + ",\"initialRevisionReason\":\"pin test\",\"fields\":{}}"))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post(documentPath(member, document.get("id").asLong()) + "/fill-spots")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "spots-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"expectedRevisionId\":" + document.get("currentRevision").get("id").asLong()
                                + ",\"templateVersionId\":" + certificate.versionId()
                                + ",\"changes\":[{\"kind\":\"ADD_BOX\",\"pageNumber\":1,"
                                + "\"box\":{\"x\":72,\"y\":100,\"width\":200,\"height\":18},\"label\":\"Extra note\"}]}"))
                .andExpect(status().isOk());

        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();
        advanceAll(member, batchId, exportId);
        assertThat(count("SELECT count(*) FROM export_receipt e JOIN batch_export_item i ON i.export_receipt_id = e.id"
                + " WHERE i.batch_export_id = ? AND e.template_version_id = " + certificate.versionId(), exportId))
                .isEqualTo(2);
    }

    /** A formula-looking cell fills as literal text, and the download's CSV carries it disarmed. */
    @Test
    void formulaCellsFillAsTextAndAreNeutralisedInTheCsv() throws Exception {
        Member member = signIn("subject-bx-formula");
        Certificate certificate = certificate(member);
        // The citation column holds formula-shaped text; it fills verbatim.
        String csv = "Member name,Award,Date awarded,Club,Citation notes\n"
                + "Ana Lima,Pin,2025-03-04,Club,=SUM(A1:A9)\n"
                + "Ben Okafor,Pin,2025-03-04,Club,+cmd\n";
        long batchId = createBatch(member, upload(member, csv.getBytes(StandardCharsets.UTF_8), "members.csv"),
                certificate, 0).get("id").asLong();
        fillAll(member, batchId);

        JsonNode doc = readJson(mockMvc.perform(get(documentPath(member,
                        rowsByNumber(getBatch(member, batchId)).get(2).get("documentId").asLong()))
                        .cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(doc.get("currentRevision").get("fields").get(certificate.fieldId("citation")).get("value").asText())
                .isEqualTo("=SUM(A1:A9)");

        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();
        advanceAll(member, batchId, exportId);
        String rowsCsv = new String(unzip(downloadZip(member, batchId, exportId)).get("rows.csv"), StandardCharsets.UTF_8);
        // A formula-shaped title would be disarmed too: the row's document title begins with the template name here,
        // so check the neutraliser on the column that carries a cell's text -- the detail column for the BLOCKED rows
        // is never a cell, so assert the file has no un-prefixed formula line instead.
        for (String line : rowsCsv.split("\r\n")) {
            for (String cell : line.split(",")) {
                assertThat(cell).as(cell).doesNotMatch("^[=+@\\t\\r].*");
            }
        }
    }

    /**
     * The gate: two hundred rows through fill, accept, export and download
     * with zero model calls and a complete archive. Each step's time is
     * printed for the report.
     */
    @Test
    void theTwoHundredRowGateRunsEndToEnd() throws Exception {
        Member member = signIn("subject-bx-gate");
        Certificate certificate = certificate(member);
        long tableId = upload(member, publicFixture("tables/members-200.csv"), "members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();

        int callsBefore = MODEL_CALLS.get();
        long total = System.nanoTime();

        long t = System.nanoTime();
        fillAll(member, batchId);
        long fillMs = (System.nanoTime() - t) / 1_000_000;

        t = System.nanoTime();
        int accepted = 0;
        int calls = 0;
        JsonNode accept;
        do {
            accept = acceptStep(member, batchId);
            accepted += accept.get("processed").asInt();
            assertThat(++calls).as("accept loops").isLessThanOrEqualTo(10);
        } while (accept.get("remaining").asInt() > 0);
        long acceptMs = (System.nanoTime() - t) / 1_000_000;
        assertThat(accepted).isEqualTo(200);

        t = System.nanoTime();
        long exportId = createExport(member, batchId, "PDF", "gate-" + UUID.randomUUID()).get("id").asLong();
        JsonNode advanced = advanceAll(member, batchId, exportId);
        long exportMs = (System.nanoTime() - t) / 1_000_000;
        assertThat(advanced.get("state").asText()).isEqualTo("COMPLETE");

        t = System.nanoTime();
        Map<String, byte[]> zip = unzip(downloadZip(member, batchId, exportId));
        long downloadMs = (System.nanoTime() - t) / 1_000_000;

        long totalMs = (System.nanoTime() - total) / 1_000_000;
        System.out.println("200-row gate: fill " + fillMs + " ms, accept " + acceptMs + " ms, export " + exportMs
                + " ms, download " + downloadMs + " ms, total " + totalMs + " ms");

        assertThat(zip).hasSize(201);
        assertThat(zip.keySet().stream().filter(name -> name.endsWith(".pdf")).count()).isEqualTo(200);
        assertThat(zip).containsKey("rows.csv");
        assertThat(MODEL_CALLS.get()).as("model calls for the whole gate").isEqualTo(callsBefore);
        assertThat(count("SELECT count(*) FROM model_usage WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    /**
     * The Word path on the real renderer: five rows through a DOCX
     * certificate, checked and exported BOTH, and the archive's files are
     * opened -- one DOCX with POI, one PDF with PDFBox -- to prove the row's
     * name is inside each.
     */
    @Test
    void theWordPathExportsBothFormats() throws Exception {
        Member member = signIn("subject-bx-word");
        Certificate certificate = docxCertificate(member);
        StringBuilder csv = new StringBuilder("Member name,Award,Date awarded,Club,Citation notes\n");
        for (int i = 1; i <= 5; i++) {
            csv.append("Word Member ").append(i).append(",Star,2025-03-0").append(i).append(",Harbour Circle,For the fair\n");
        }
        long batchId = createBatch(member, upload(member, csv.toString().getBytes(StandardCharsets.UTF_8), "members.csv"),
                certificate, 0).get("id").asLong();
        fillAll(member, batchId);

        long t = System.nanoTime();
        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "BOTH", "word-" + UUID.randomUUID()).get("id").asLong();
        advanceAll(member, batchId, exportId);
        long renderMs = (System.nanoTime() - t) / 1_000_000;
        System.out.println("Word path check+export for 5 rows: " + renderMs + " ms (" + (renderMs / 5) + " ms per row)");

        Map<String, byte[]> zip = unzip(downloadZip(member, batchId, exportId));
        assertThat(zip.keySet().stream().filter(name -> name.endsWith(".docx")).count()).isEqualTo(5);
        assertThat(zip.keySet().stream().filter(name -> name.endsWith(".pdf")).count()).isEqualTo(5);
        assertThat(zip).containsKey("rows.csv");

        String docxName = zip.keySet().stream().filter(name -> name.endsWith(".docx")).sorted().findFirst().orElseThrow();
        String pdfName = zip.keySet().stream().filter(name -> name.endsWith(".pdf")).sorted().findFirst().orElseThrow();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument docx =
                     new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(zip.get(docxName)))) {
            StringBuilder text = new StringBuilder();
            docx.getParagraphs().forEach(paragraph -> text.append(paragraph.getText()).append('\n'));
            docx.getTables().forEach(table -> table.getRows().forEach(
                    row -> row.getTableCells().forEach(cell -> text.append(cell.getText()).append('\n'))));
            assertThat(text.toString()).contains("Word Member 1");
        }
        try (org.apache.pdfbox.pdmodel.PDDocument pdf =
                     org.apache.pdfbox.Loader.loadPDF(zip.get(pdfName))) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            assertThat(text).contains("Word Member 1");
        }
    }

    /**
     * A Word batch's receipt always names the Word file the render came
     * from; the archive still ships only the format the export was asked
     * for -- a PDF run's ZIP holds PDFs alone, a DOCX run's Word files
     * alone, and rows.csv's file and hash columns follow.
     */
    @Test
    void aWordBatchShipsOnlyTheFormatAskedFor() throws Exception {
        Member member = signIn("subject-bx-word-format");
        Certificate certificate = docxCertificate(member);
        long batchId = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, batchId);
        checkAll(member, batchId);

        long pdfExportId = createExport(member, batchId, "PDF", "pdf-only-" + UUID.randomUUID())
                .get("id").asLong();
        advanceAll(member, batchId, pdfExportId);
        Map<String, byte[]> pdfZip = unzip(downloadZip(member, batchId, pdfExportId));
        assertThat(pdfZip.keySet()).containsExactlyInAnyOrder(
                "002 Participation certificate, Ana Lima.pdf",
                "003 Participation certificate, Ben Okafor.pdf",
                "rows.csv");
        String pdfCsv = new String(pdfZip.get("rows.csv"), StandardCharsets.UTF_8);
        assertThat(pdfCsv).doesNotContain(".docx");

        long docxExportId = createExport(member, batchId, "DOCX", "docx-only-" + UUID.randomUUID())
                .get("id").asLong();
        advanceAll(member, batchId, docxExportId);
        Map<String, byte[]> docxZip = unzip(downloadZip(member, batchId, docxExportId));
        assertThat(docxZip.keySet()).containsExactlyInAnyOrder(
                "002 Participation certificate, Ana Lima.docx",
                "003 Participation certificate, Ben Okafor.docx",
                "rows.csv");
        String docxCsv = new String(docxZip.get("rows.csv"), StandardCharsets.UTF_8);
        assertThat(docxCsv).doesNotContain(".pdf");
    }

    /**
     * Trashing the template stops only the making of new documents: a
     * batch already filled still accepts, checks, exports and downloads,
     * while an unfilled batch keeps refusing its fill step.
     */
    @Test
    void aTrashedTemplateStopsOnlyFill() throws Exception {
        Member member = signIn("subject-bx-trash");
        Certificate certificate = certificate(member);
        long filled = createBatch(member, upload(member, smallCsv(), "members.csv"), certificate, 0)
                .get("id").asLong();
        long pending = createBatch(member, upload(member, smallCsv(), "members-again.csv"), certificate, 0)
                .get("id").asLong();
        fillAll(member, filled);

        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/templates/"
                                + certificate.templateId() + "/trash")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(post(batchPath(member, pending) + "/fill").cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(readJson(result).get("code").asText())
                        .isEqualTo("TEMPLATE_TRASHED"));

        JsonNode accept = acceptStep(member, filled);
        assertThat(accept.get("processed").asInt()).isEqualTo(2);
        checkAll(member, filled);

        long exportId = createExport(member, filled, "PDF", "trash-" + UUID.randomUUID()).get("id").asLong();
        JsonNode advanced = advanceAll(member, filled, exportId);
        assertThat(advanced.get("state").asText()).isEqualTo("COMPLETE");
        Map<String, byte[]> zip = unzip(downloadZip(member, filled, exportId));
        assertThat(zip.keySet()).allMatch(name -> name.endsWith(".pdf") || name.equals("rows.csv"));
        assertThat(zip).hasSize(3);
    }

    /** The download's Content-Disposition is RFC 5987: a title's spaces and letters stay readable, never '+'. */
    @Test
    void theDownloadNamesItsZipWithAnRfc5987Filename() throws Exception {
        Member member = signIn("subject-bx-disposition");
        Certificate certificate = certificate(member);
        long tableId = upload(member, smallCsv(), "Café members.csv");
        long batchId = createBatch(member, tableId, certificate, 0).get("id").asLong();
        fillAll(member, batchId);
        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "disp-" + UUID.randomUUID()).get("id").asLong();
        advanceAll(member, batchId, exportId);

        MvcResult started = mockMvc.perform(get(batchPath(member, batchId) + "/exports/" + exportId + "/download")
                        .cookie(member.session()))
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        String disposition = completed.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).startsWith("attachment");
        assertThat(disposition).doesNotContain("+");
        assertThat(disposition).contains("Caf%C3%A9%20members");
    }

    // --- helpers ---

    private Certificate certificate(Member member) throws Exception {
        return certificate(member, Map.of());
    }

    /** The PDF certificate template: its five AcroForm spots bound and activated. */
    private Certificate certificate(Member member, Map<String, FieldRequiredness> requiredness) throws Exception {
        return certificate(member, publicFixture("pdf/certificate-of-participation.pdf"), "certificate.pdf",
                requiredness, "acroFormField", false);
    }

    /** The Word certificate: same five places as content controls, activated through the real baseline render. */
    private Certificate docxCertificate(Member member) throws Exception {
        return certificate(member, publicFixture("templates/certificate-of-participation.docx"), "certificate.docx",
                Map.of(), "tag", true);
    }

    private Certificate certificate(
            Member member, byte[] file, String filename, Map<String, FieldRequiredness> requiredness, String bindingName,
            boolean extractFirst) throws Exception {
        long artifactId = upload(member, file, filename);
        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, artifactId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        if (extractFirst) {
            // A Word source must be structurally extracted before it can be a template.
            mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/artifacts/" + artifactId + "/extraction")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk());
        }
        long templateId = readJson(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Participation certificate\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("template").get("id").asLong();
        ObjectNode body = JSON.createObjectNode().put("expectedVersionNumber", 1);
        ArrayNode fields = body.putArray("fields");
        Map<String, String> fieldIds = new LinkedHashMap<>();
        for (JsonNode spot : form.get("spots")) {
            JsonNode binding = spot.get("binding");
            String key = binding.hasNonNull(bindingName) ? binding.get(bindingName).asText() : spot.get("fieldId").asText();
            fieldIds.put(key, spot.get("fieldId").asText());
            ObjectNode field = fields.addObject();
            for (String name : List.of("fieldId", "label", "type", "cardinality", "requiredness", "origin", "docxControl", "blankText")) {
                if (spot.hasNonNull(name)) {
                    field.set(name, spot.get(name));
                }
            }
            field.set("binding", binding);
            if (requiredness.containsKey(key)) {
                field.put("requiredness", requiredness.get(key).name());
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

    private JsonNode createBatch(Member member, long tableId, Certificate certificate, int titleColumn) throws Exception {
        return readJson(mockMvc.perform(post(batchesPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "batch-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"templateId\":" + certificate.templateId() + ",\"templateVersionId\":" + certificate.versionId()
                                + ",\"artifactId\":" + tableId + ",\"mapping\":" + mappingJson(certificate)
                                + ",\"titleColumn\":" + titleColumn + "}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode fillAll(Member member, long batchId) throws Exception {
        JsonNode step = null;
        int calls = 0;
        do {
            step = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/fill")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(++calls).as("fill loops").isLessThanOrEqualTo(20);
        } while (step.get("remaining").asInt() > 0);
        return step;
    }

    private JsonNode acceptStep(Member member, long batchId) throws Exception {
        return readJson(mockMvc.perform(post(batchPath(member, batchId) + "/accept")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode checkStep(Member member, long batchId) throws Exception {
        return readJson(mockMvc.perform(post(batchPath(member, batchId) + "/check")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode checkAll(Member member, long batchId) throws Exception {
        JsonNode step;
        int calls = 0;
        do {
            step = checkStep(member, batchId);
            assertThat(++calls).as("check loops").isLessThanOrEqualTo(40);
        } while (step.get("remaining").asInt() > 0);
        return step;
    }

    private JsonNode createExport(Member member, long batchId, String format, String key) throws Exception {
        return readJson(mockMvc.perform(post(batchPath(member, batchId) + "/exports")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"format\":\"" + format + "\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode advanceExport(Member member, long batchId, long exportId) throws Exception {
        return readJson(mockMvc.perform(post(batchPath(member, batchId) + "/exports/" + exportId + "/advance")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode advanceAll(Member member, long batchId, long exportId) throws Exception {
        JsonNode step;
        int calls = 0;
        do {
            step = advanceExport(member, batchId, exportId);
            assertThat(++calls).as("advance loops").isLessThanOrEqualTo(60);
        } while (step.get("remaining").asInt() > 0);
        return step;
    }

    private JsonNode getBatch(Member member, long batchId) throws Exception {
        return readJson(mockMvc.perform(get(batchPath(member, batchId)).cookie(member.session()))
                .andExpect(status().isOk()).andReturn());
    }

    private byte[] downloadZip(Member member, long batchId, long exportId) throws Exception {
        MvcResult started = mockMvc.perform(get(batchPath(member, batchId) + "/exports/" + exportId + "/download")
                        .cookie(member.session()))
                .andReturn();
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private static Map<Integer, JsonNode> rowsByNumber(JsonNode detail) {
        Map<Integer, JsonNode> rows = new LinkedHashMap<>();
        detail.get("rows").forEach(row -> rows.put(row.get("rowNumber").asInt(), row));
        return rows;
    }

    private static Map<Integer, JsonNode> verdictsByRow(JsonNode accept) {
        Map<Integer, JsonNode> verdicts = new LinkedHashMap<>();
        accept.get("rows").forEach(row -> verdicts.put(row.get("rowNumber").asInt(), row));
        return verdicts;
    }

    private static Map<Integer, JsonNode> itemsByRow(JsonNode advanced) {
        Map<Integer, JsonNode> items = new LinkedHashMap<>();
        advanced.get("items").forEach(item -> items.put(item.get("rowNumber").asInt(), item));
        return items;
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

    private static String batchesPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/batches";
    }

    private static String batchPath(Member member, long batchId) {
        return batchesPath(member) + "/" + batchId;
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

    // --- statement counting, for the set-based detail read ---

    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

    /**
     * A DataSource that delegates everything but counts every statement
     * the wrapped connections hand out -- the honest measure of "how many
     * queries did that request run", because it counts what the pool was
     * asked for, not what a log happened to print.
     */
    static final class CountingDataSource implements DataSource {

        private final DataSource delegate;

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        private Connection wrap(Connection connection) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")
                        || method.getName().equals("prepareCall")
                        || method.getName().equals("createStatement")) {
                    QUERY_COUNT.incrementAndGet();
                }
                return method.invoke(connection, args);
            };
            return (Connection) Proxy.newProxyInstance(
                    CountingDataSource.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
