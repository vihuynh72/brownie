package io.github.vihuynh72.brownie.api.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The compose lane end to end with a stand-in model: rows with notes end
 * PROPOSED holding a patch proposal a person still has to take, rows with
 * nothing to say end NOT_NEEDED, a reply that will not parse or a model
 * that cannot be reached fails only its own row, and the allowance being
 * spent stops the step exactly where it stood -- while fill, accept,
 * check and export carry on around it all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.batch.compose-offered=true",
        "brownie.fill-spots.model=enabled"})
@DockerTest
@Import(BatchComposeIntegrationTest.ComposeGatewayConfig.class)
class BatchComposeIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-batch-compose";

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
    /** What the stand-in answers next: a reply body, or a failure to throw. */
    static final Queue<Object> NEXT_COMPLETION = new ConcurrentLinkedQueue<>();

    /**
     * The stand-in composes exactly the shape asked for: it reads the
     * reply schema the request carries to learn which place ids it must
     * answer, and writes the queued text (or fails the queued way). A real
     * model never runs here. Queue entries: a {@link ModelCompletion} is
     * handed back as-is, a {@link RuntimeException} is thrown, a
     * {@link String} becomes the text of every place asked for.
     */
    @TestConfiguration
    static class ComposeGatewayConfig {
        @Bean
        @Primary
        ModelGateway composeModelGateway() {
            return request -> {
                MODEL_CALLS.incrementAndGet();
                if (request.promptVersion().equals("fill-spots-v3")) {
                    // Compose being offered turns model spot-naming on too;
                    // the stand-in keeps every spot it is asked about, named
                    // for its own id, so the certificate binds as always.
                    return new ModelCompletion.Success(namingReplyJson(request), new ModelUsage(80, 60));
                }
                assertThat(request.promptVersion()).isEqualTo("batch-compose-v1");
                Object next = NEXT_COMPLETION.poll();
                if (next instanceof ModelTransportException failure) {
                    throw failure;
                }
                if (next instanceof RuntimeException failure) {
                    throw failure;
                }
                if (next instanceof ModelCompletion completion) {
                    return completion;
                }
                if (next instanceof ModelUsage usage) {
                    return new ModelCompletion.Success(replyJson(request, "For the food drive"), usage);
                }
                String text = next instanceof String s ? s : "For the food drive";
                return new ModelCompletion.Success(replyJson(request, text), new ModelUsage(120, 40));
            };
        }

        private static String namingReplyJson(ModelRequest request) {
            try {
                JsonNode schema = new ObjectMapper().readTree(request.responseSchema().schemaJson());
                StringBuilder reply = new StringBuilder("{\"spots\":[");
                boolean first = true;
                for (JsonNode id : schema.at("/properties/spots/items/properties/id/enum")) {
                    if (!first) {
                        reply.append(',');
                    }
                    reply.append("{\"id\":\"").append(id.asText())
                            .append("\",\"keep\":true,\"label\":\"").append(id.asText())
                            .append("\",\"type\":\"TEXT\",\"required\":false}");
                    first = false;
                }
                return reply.append("],\"repeatingRow\":null}").toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static String replyJson(ModelRequest request, String text) {
            try {
                JsonNode schema = new ObjectMapper().readTree(request.responseSchema().schemaJson());
                StringBuilder reply = new StringBuilder("{\"places\":{");
                JsonNode places = schema.at("/properties/places/properties");
                boolean first = true;
                for (var it = places.fieldNames(); it.hasNext();) {
                    if (!first) {
                        reply.append(',');
                    }
                    reply.append('"').append(it.next()).append("\":{\"text\":\"").append(text).append("\"}");
                    first = false;
                }
                return reply.append("}}").toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
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

    private record Member(Cookie session, long workspaceId, long userId) {}

    /**
     * Notes that say nothing worth writing are NOT_NEEDED and are never
     * sent to the model; notes with something in them come back as a
     * proposal, which waits on a person like every suggestion.
     */
    @Test
    void notesComposeIntoProposalsAndBlankNotesNeedNothing() throws Exception {
        Member member = signIn("subject-bc-lane");
        NEXT_COMPLETION.clear();
        long batchId = composeBatch(member, "notes-compose-1");
        fillAll(member, batchId);
        int callsBefore = MODEL_CALLS.get();

        JsonNode step = composeStep(member, batchId);
        assertThat(step.get("remaining").asInt()).isZero();
        Map<Integer, JsonNode> rows = rowsByNumber(step.get("rows"));
        assertThat(rows.get(2).get("composeState").asText()).isEqualTo("PROPOSED");
        assertThat(rows.get(2).get("proposalId").isNumber()).isTrue();
        assertThat(rows.get(3).get("composeState").asText()).isEqualTo("NOT_NEEDED");
        assertThat(rows.get(3).get("proposalId").isNull()).isTrue();
        // One row's notes, one call: the blank row never went near the model.
        assertThat(MODEL_CALLS.get() - callsBefore).isEqualTo(1);

        JsonNode detail = getBatch(member, batchId);
        JsonNode proposed = rowsByNumber(detail.get("rows")).get(2);
        assertThat(proposed.get("suggestionWaiting").asBoolean()).isTrue();

        // While the suggestion waits the row cannot be accepted.
        JsonNode refused = acceptStep(member, batchId);
        assertThat(verdictsByRow(refused).get(2).get("code").asText()).isEqualTo("SUGGESTION_WAITING");
        assertThat(verdictsByRow(refused).get(3).get("result").asText()).isEqualTo("ACCEPTED");

        // The person takes the suggestion. What it wrote keeps its
        // AI_COMPOSED authorship, so batch accept still refuses the row --
        // a model's wording is a person's call -- while the row it had no
        // notes for is already done and nothing stands in the way of
        // checking and shipping either of them.
        acceptProposal(member, proposed.get("documentId").asLong(),
                proposed.get("proposalId").asLong(), proposed.get("currentRevisionId").asLong());
        JsonNode secondAccept = acceptStep(member, batchId);
        assertThat(verdictsByRow(secondAccept).get(2).get("code").asText()).isEqualTo("AI_COMPOSED");
        assertThat(secondAccept.get("processed").asInt()).isZero();
        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();
        JsonNode advanced = advanceAll(member, batchId, exportId);
        for (JsonNode item : advanced.get("items")) {
            assertThat(item.get("outcome").asText()).isEqualTo("EXPORTED");
        }
        assertThat(unzip(downloadZip(member, batchId, exportId))).hasSize(3);
    }

    /** When the month is spent the row names it NO_ALLOWANCE and the step stops right there. */
    @Test
    void aSpentAllowanceStopsTheStepWhereItStood() throws Exception {
        Member member = signIn("subject-bc-quota");
        NEXT_COMPLETION.clear();
        long batchId = composeBatch(member, "notes-quota", List.of(
                "notes for row two", "notes for row three", "notes for row four"));
        fillAll(member, batchId);
        int callsBefore = MODEL_CALLS.get();

        // The workspace's month is spent down to less than two requests,
        // so the first row composes -- its own billed cost finishing the
        // month off -- and the row after it finds nothing left to reserve.
        // The row after that is never even asked about.
        seedSettled(member.workspaceId(), member.userId(), "1.9990");
        NEXT_COMPLETION.add(new ModelUsage(100, 3000));

        JsonNode step = composeStep(member, batchId);
        Map<Integer, JsonNode> rows = rowsByNumber(step.get("rows"));
        assertThat(rows.get(2).get("composeState").asText()).isEqualTo("PROPOSED");
        assertThat(MODEL_CALLS.get() - callsBefore).isEqualTo(1);

        // The step lists only the rows it finished; the stop's own marks
        // are read back on the batch.
        Map<Integer, JsonNode> afterStop = rowsByNumber(getBatch(member, batchId).get("rows"));
        assertThat(afterStop.get(3).get("composeState").asText()).isEqualTo("NO_ALLOWANCE");
        assertThat(afterStop.get(4).get("composeState").asText()).isEqualTo("PENDING");

        // And the rest of the lane is untouched by the shortage: resolve the
        // proposal, then accept, check and export all three rows. The row
        // that took the model's wording keeps its AI_COMPOSED authorship and
        // so stays out of the batch accept; the other two accept.
        JsonNode proposed = afterStop.get(2);
        acceptProposal(member, proposed.get("documentId").asLong(),
                proposed.get("proposalId").asLong(), proposed.get("currentRevisionId").asLong());
        JsonNode accept = acceptStep(member, batchId);
        assertThat(accept.get("processed").asInt()).isEqualTo(2);
        assertThat(verdictsByRow(accept).get(2).get("code").asText()).isEqualTo("AI_COMPOSED");
        checkAll(member, batchId);
        long exportId = createExport(member, batchId, "PDF", "exp-" + UUID.randomUUID()).get("id").asLong();
        JsonNode advanced = advanceAll(member, batchId, exportId);
        for (JsonNode item : advanced.get("items")) {
            assertThat(item.get("outcome").asText()).isEqualTo("EXPORTED");
        }
    }

    /** A reply that does not parse fails its own row; the row after it still composes. */
    @Test
    void anUnreadableReplyFailsOnlyItsOwnRow() throws Exception {
        Member member = signIn("subject-bc-unreadable");
        NEXT_COMPLETION.clear();
        // The first reply is not even JSON; the second is a good answer for its row.
        NEXT_COMPLETION.add(new ModelCompletion.Success("NOT-JSON", new ModelUsage(100, 10)));
        NEXT_COMPLETION.add("For the winter fair");
        long batchId = composeBatch(member, "notes-unreadable", List.of("notes a", "notes b"));
        fillAll(member, batchId);
        int callsBefore = MODEL_CALLS.get();

        JsonNode step = composeStep(member, batchId);
        Map<Integer, JsonNode> rows = rowsByNumber(step.get("rows"));
        assertThat(rows.get(2).get("composeState").asText()).isEqualTo("FAILED");
        assertThat(rows.get(3).get("composeState").asText()).isEqualTo("PROPOSED");
        assertThat(MODEL_CALLS.get() - callsBefore).isEqualTo(2);
    }

    /** A model that cannot be reached fails that row and the step continues past it. */
    @Test
    void anUnreachableModelFailsOnlyItsOwnRow() throws Exception {
        Member member = signIn("subject-bc-transport");
        NEXT_COMPLETION.clear();
        NEXT_COMPLETION.add(new ModelTransportException("connection refused", true, null));
        NEXT_COMPLETION.add("For the winter fair");
        long batchId = composeBatch(member, "notes-transport", List.of("notes a", "notes b"));
        fillAll(member, batchId);
        int callsBefore = MODEL_CALLS.get();

        JsonNode step = composeStep(member, batchId);
        Map<Integer, JsonNode> rows = rowsByNumber(step.get("rows"));
        assertThat(rows.get(2).get("composeState").asText()).isEqualTo("FAILED");
        assertThat(rows.get(3).get("composeState").asText()).isEqualTo("PROPOSED");
        // A direct request's own bound is one physical call, so a transport
        // failure stands as soon as it lands: one ask for the lost row, one
        // for the row that composed.
        assertThat(MODEL_CALLS.get() - callsBefore).isEqualTo(2);
    }

    // --- helpers ---

    /**
     * Writes a settled ledger row as the database owner -- the api role
     * may never write one itself -- so a test can spend part of the
     * workspace's month before the step runs.
     */
    private static void seedSettled(long workspaceId, long userId, String costUsd) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card, state,
                                         reserved_input_tokens, reserved_output_tokens, reserved_cost_usd,
                                         actual_input_tokens, actual_output_tokens, actual_cost_usd, created_at, closed_at)
                VALUES (?, ?, 'ASSIST', 'seeded-model', 'seeded-prompt', 'seeded rates', 'SETTLED', 1, 1, 0.100000, 1, 1, ?::numeric,
                        now(), now())
                """)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, userId);
            statement.setString(3, costUsd);
            statement.executeUpdate();
        }
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    /** A batch with citation COMPOSE-mapped over the notes column; other columns map normally. */
    private long composeBatch(Member member, String key) throws Exception {
        return composeBatch(member, key, List.of("helped run the food drive", ""));
    }

    private long composeBatch(Member member, String key, List<String> notes) throws Exception {
        Certificate certificate = certificate(member);
        StringBuilder csv = new StringBuilder("Member name,Award,Date awarded,Club,Citation notes\n");
        for (int i = 0; i < notes.size(); i++) {
            csv.append("Member ").append(i + 1).append(",Pin,2025-03-0").append(i + 1)
                    .append(",Club,\"").append(notes.get(i)).append("\"\n");
        }
        long tableId = upload(member, csv.toString().getBytes(StandardCharsets.UTF_8), "members.csv");
        String mapping = "["
                + "{\"fieldId\":\"" + certificate.fieldId("member.name") + "\",\"column\":0},"
                + "{\"fieldId\":\"" + certificate.fieldId("award.title") + "\",\"column\":1},"
                + "{\"fieldId\":\"" + certificate.fieldId("award.date") + "\",\"column\":2},"
                + "{\"fieldId\":\"" + certificate.fieldId("club.name") + "\",\"column\":3},"
                + "{\"fieldId\":\"" + certificate.fieldId("citation") + "\",\"column\":4,\"mode\":\"COMPOSE\"}]";
        JsonNode batch = readJson(mockMvc.perform(post(batchesPath(member)).cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", key + "-" + UUID.randomUUID()).contentType("application/json")
                        .content("{\"templateId\":" + certificate.templateId() + ",\"templateVersionId\":" + certificate.versionId()
                                + ",\"artifactId\":" + tableId + ",\"mapping\":" + mapping + ",\"titleColumn\":0}"))
                .andExpect(status().isCreated()).andReturn());
        return batch.get("id").asLong();
    }

    private record Certificate(long artifactId, long templateId, long versionId, Map<String, String> fieldIds) {

        String fieldId(String formField) {
            return fieldIds.get(formField);
        }
    }

    private Certificate certificate(Member member) throws Exception {
        long artifactId = upload(member, publicFixture("pdf/certificate-of-participation.pdf"), "certificate.pdf");
        JsonNode form = readJson(mockMvc.perform(post(fillableFormPath(member, artifactId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        long templateId = readJson(mockMvc.perform(post(templatesPath(member)).cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Participation certificate\",\"sourceArtifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("template").get("id").asLong();
        com.fasterxml.jackson.databind.node.ObjectNode body = JSON.createObjectNode().put("expectedVersionNumber", 1);
        com.fasterxml.jackson.databind.node.ArrayNode fields = body.putArray("fields");
        Map<String, String> fieldIds = new LinkedHashMap<>();
        for (JsonNode spot : form.get("spots")) {
            fieldIds.put(spot.get("binding").get("acroFormField").asText(), spot.get("fieldId").asText());
            com.fasterxml.jackson.databind.node.ObjectNode field = fields.addObject();
            for (String name : List.of("fieldId", "label", "type", "cardinality", "requiredness", "origin", "docxControl", "blankText")) {
                if (spot.hasNonNull(name)) {
                    field.set(name, spot.get(name));
                }
            }
            field.set("binding", spot.get("binding"));
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
        return new Certificate(artifactId, templateId, readJson(activation).get("id").asLong(), fieldIds);
    }

    private JsonNode composeStep(Member member, long batchId) throws Exception {
        return readJson(mockMvc.perform(post(batchPath(member, batchId) + "/compose")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
    }

    private void acceptProposal(Member member, long documentId, long proposalId, long expectedRevisionId) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/documents/" + documentId
                        + "/patch-proposals/" + proposalId + "/accept")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", "patch-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + expectedRevisionId + "}"))
                .andExpect(status().isOk());
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

    private JsonNode checkAll(Member member, long batchId) throws Exception {
        JsonNode step;
        int calls = 0;
        do {
            step = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/check")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()).andReturn());
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

    private JsonNode advanceAll(Member member, long batchId, long exportId) throws Exception {
        JsonNode step;
        int calls = 0;
        do {
            step = readJson(mockMvc.perform(post(batchPath(member, batchId) + "/exports/" + exportId + "/advance")
                            .cookie(member.session()).with(csrf()))
                    .andExpect(status().isOk()).andReturn());
            assertThat(++calls).as("advance loops").isLessThanOrEqualTo(40);
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

    private static Map<Integer, JsonNode> rowsByNumber(JsonNode rows) {
        Map<Integer, JsonNode> byNumber = new LinkedHashMap<>();
        rows.forEach(row -> byNumber.put(row.get("rowNumber").asInt(), row));
        return byNumber;
    }

    private static Map<Integer, JsonNode> verdictsByRow(JsonNode accept) {
        return rowsByNumber(accept.get("rows"));
    }

    private long upload(Member member, byte[] bytes, String filename) throws Exception {
        long artifactId = readJson(mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads")
                        .cookie(member.session()).with(csrf()).contentType("application/json")
                        .content("{\"filename\":\"" + filename + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/content")
                        .cookie(member.session()).with(csrf()).contentType("application/octet-stream").content(bytes))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/uploads/" + artifactId + "/complete")
                        .cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        return artifactId;
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
