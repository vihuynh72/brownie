package io.github.vihuynh72.brownie.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole fill conversation end to end: a pasted text becomes a
 * source, {@code propose_fill} starts the real extraction run and waits
 * on it, the proposal is confirmed exactly once, and the exported file
 * downloads through a single-use link. No worker runs in an API-module
 * test, so the test publishes the staged result rows a real worker would
 * have written -- the same fabrication
 * {@link io.github.vihuynh72.brownie.api.generation.GenerationApplyIntegrationTest}
 * uses -- while the call sits inside its polling budget.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class McpFillLifecycleIntegrationTest extends McpIntegrationTestSupport {

    private static final String ISSUER = "https://issuer-mcp-fill";
    private static final String RESULT_JSON = """
            {"scalarCandidates":{\
            "meeting.title":{"fieldId":"meeting.title","value":"Weekly Sync","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null},\
            "meeting.date":{"fieldId":"meeting.date","value":"2026-03-12","evidenceSpanIds":[],"unresolved":false,"ambiguityReason":null}},\
            "repeatedItems":[]}""";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, DB);
    }

    @Autowired
    private BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    @Autowired
    private BlobStore blobStore;

    @Test
    void theWholeTextToDownloadedDocxPathWithExactlyOneConfirmation() throws Exception {
        Browser browser = signIn("subject-mcp-happy", ISSUER);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(browser.workspaceId(), browser.userId());
        IssuedToken token = issueToken(browser, "happy", "[\"read\",\"fill\",\"export\"]");
        McpSyncClient client = mcpClient(token.token());

        long templateId = templateIdByName(client, "Flowing meeting minutes");
        JsonNode started = toolResult(client, "start_document", Map.of("template_id", templateId, "title", "Sync"));
        long documentId = started.get("document_id").asLong();
        assertThat(started.get("fill_spots").get(0).has("label")).isTrue();
        assertThat(started.get("fill_spots").get(0).has("field_id")).isTrue();

        JsonNode source = toolResult(client, "add_text_source",
                Map.of("document_id", documentId, "text", "The weekly sync moved to Thursday.", "name", "notes.txt"));
        long artifactId = source.get("source_artifact_id").asLong();

        JsonNode proposed = proposeUntilSettled(client, browser.workspaceId(), documentId, artifactId, RESULT_JSON);
        assertThat(proposed.get("state").asText()).isEqualTo("PROPOSED");
        long runId = proposed.get("run_id").asLong();
        long proposalId = proposed.get("proposal_id").asLong();
        String payloadHash = proposed.get("payload_hash").asText();
        assertThat(payloadHash).matches("[0-9a-f]{64}");
        JsonNode firstSummary = proposed.get("summary").get(0);
        assertThat(firstSummary.has("field_id")).isTrue();
        assertThat(firstSummary.get("label").asText()).isNotBlank();

        // A second status read names the same proposal: the result was
        // applied exactly once, not once per look.
        JsonNode again = toolResult(client, "get_fill_status", Map.of("document_id", documentId, "run_id", runId));
        assertThat(again.get("state").asText()).isEqualTo("PROPOSED");
        assertThat(again.get("proposal_id").asLong()).isEqualTo(proposalId);
        assertThat(again.get("payload_hash").asText()).isEqualTo(payloadHash);

        JsonNode confirmed = toolResult(client, "confirm_fill",
                Map.of("proposal_id", proposalId, "payload_hash", payloadHash));
        assertThat(confirmed.get("revision_id").asLong()).isPositive();
        assertThat(confirmed.get("applied_fields")).isNotEmpty();

        JsonNode exported = toolResult(client, "export_document",
                Map.of("document_id", documentId, "format", "docx"));
        long receiptId = exported.get("receipt_id").asLong();
        assertThat(exported.get("files").get("docx").asBoolean()).isTrue();

        JsonNode link = toolResult(client, "get_export_link", Map.of("receipt_id", receiptId, "file", "docx"));
        String path = URI.create(link.get("url").asText()).getPath();
        HttpResponse<byte[]> downloaded = http.send(
                java.net.http.HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(new String(downloaded.body(), 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");

        // The document's recorded history holds every tool call it saw.
        JsonNode history = readJson(get(browser,
                "/api/v1/workspaces/" + browser.workspaceId() + "/documents/" + documentId + "/mcp-calls", 200));
        List<String> tools = new java.util.ArrayList<>();
        history.forEach(row -> tools.add(row.get("tool").asText()));
        // Calls that name the document land in its history; calls that
        // name nothing document-shaped yet (list_templates) are recorded
        // against the grant instead and stay out of this listing.
        assertThat(tools).contains("start_document", "add_text_source", "propose_fill",
                "get_fill_status", "confirm_fill", "export_document", "get_export_link");
    }

    @Test
    void aReplayedConfirmIsRefused() throws Exception {
        Browser browser = signIn("subject-mcp-replay", ISSUER);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(browser.workspaceId(), browser.userId());
        McpSyncClient client = mcpClient(issueToken(browser, "replay", "[\"read\",\"fill\",\"export\"]").token());
        long documentId = startDocumentOnMeetingMinutes(client);
        long artifactId = addSource(client, documentId);
        JsonNode proposed = proposeUntilSettled(client, browser.workspaceId(), documentId, artifactId, RESULT_JSON);
        long proposalId = proposed.get("proposal_id").asLong();
        String payloadHash = proposed.get("payload_hash").asText();

        JsonNode confirmed = toolResult(client, "confirm_fill",
                Map.of("proposal_id", proposalId, "payload_hash", payloadHash));
        assertThat(confirmed.get("revision_id").asLong()).isPositive();
        assertThat(confirmed.get("applied_fields")).isNotEmpty();

        JsonNode replayed = toolResult(client, "confirm_fill",
                Map.of("proposal_id", proposalId, "payload_hash", payloadHash));
        assertThat(replayed.get("refused").asText()).isEqualTo("PROPOSAL_NOT_OPEN");
    }

    @Test
    void aProposalChangedAfterItsHashWasShownIsRefused() throws Exception {
        Browser browser = signIn("subject-mcp-tamper", ISSUER);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(browser.workspaceId(), browser.userId());
        McpSyncClient client = mcpClient(issueToken(browser, "tamper", "[\"read\",\"fill\",\"export\"]").token());
        long documentId = startDocumentOnMeetingMinutes(client);
        long artifactId = addSource(client, documentId);
        JsonNode proposed = proposeUntilSettled(client, browser.workspaceId(), documentId, artifactId, RESULT_JSON);
        long proposalId = proposed.get("proposal_id").asLong();
        String payloadHash = proposed.get("payload_hash").asText();

        // The proposal moved since the hash was shown: a web edit, another
        // run -- here a direct rewrite of one proposed value.
        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE document_patch_proposal SET proposed_values = "
                                + "replace(proposed_values::text, '\"Weekly Sync\"', '\"Someone changed this\"')::jsonb "
                                + "WHERE id = ?")) {
            statement.setLong(1, proposalId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        JsonNode refused = toolResult(client, "confirm_fill",
                Map.of("proposal_id", proposalId, "payload_hash", payloadHash));
        assertThat(refused.get("refused").asText()).isEqualTo("PAYLOAD_CHANGED");
    }

    @Test
    void exportIsRefusedWhileAProposalWaitsForConfirmation() throws Exception {
        Browser browser = signIn("subject-mcp-unconfirmed", ISSUER);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(browser.workspaceId(), browser.userId());
        McpSyncClient client = mcpClient(issueToken(browser, "unconfirmed", "[\"read\",\"fill\",\"export\"]").token());
        long documentId = startDocumentOnMeetingMinutes(client);
        long artifactId = addSource(client, documentId);
        proposeUntilSettled(client, browser.workspaceId(), documentId, artifactId, RESULT_JSON);

        JsonNode refused = toolResult(client, "export_document", Map.of("document_id", documentId, "format", "docx"));
        assertThat(refused.get("refused").asText()).isEqualTo("PROPOSAL_PENDING");
    }

    @Test
    void theTokensStoredHashCannotStandInForTheTokenItself() throws Exception {
        Browser browser = signIn("subject-mcp-forge", ISSUER);
        IssuedToken token = issueToken(browser, "forge", "[\"read\"]");
        String storedHash;
        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT token_sha256 FROM mcp_grant WHERE id = ?")) {
            statement.setLong(1, token.grantId());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                storedHash = rs.getString(1);
            }
        }
        // Handing back the stored hash -- the only thing the database ever
        // kept -- resolves nothing: SHA-256 of it is a different value.
        HttpResponse<String> response = mcpRaw(storedHash,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void anExpiredDownloadLinkAnswers404() throws Exception {
        Browser browser = signIn("subject-mcp-expired", ISSUER);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(browser.workspaceId(), browser.userId());
        McpSyncClient client = mcpClient(issueToken(browser, "expired", "[\"read\",\"fill\",\"export\"]").token());
        long documentId = startDocumentOnMeetingMinutes(client);
        long artifactId = addSource(client, documentId);
        JsonNode proposed = proposeUntilSettled(client, browser.workspaceId(), documentId, artifactId, RESULT_JSON);
        toolResult(client, "confirm_fill", Map.of(
                "proposal_id", proposed.get("proposal_id").asLong(),
                "payload_hash", proposed.get("payload_hash").asText()));
        JsonNode exported = toolResult(client, "export_document", Map.of("document_id", documentId, "format", "docx"));
        JsonNode link = toolResult(client, "get_export_link",
                Map.of("receipt_id", exported.get("receipt_id").asLong(), "file", "docx"));
        String path = URI.create(link.get("url").asText()).getPath();
        String linkToken = path.substring(path.lastIndexOf('/') + 1);

        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE mcp_download_link SET created_at = now() - interval '1 hour', "
                                + "expires_at = now() - interval '1 minute' WHERE token_sha256 = ?")) {
            statement.setString(1, sha256Hex(linkToken));
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        getAnonymous(path, 404);
    }

    @Test
    void aReusedDownloadLinkAnswers404() throws Exception {
        Browser browser = signIn("subject-mcp-reused", ISSUER);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(browser.workspaceId(), browser.userId());
        McpSyncClient client = mcpClient(issueToken(browser, "reused", "[\"read\",\"fill\",\"export\"]").token());
        long documentId = startDocumentOnMeetingMinutes(client);
        long artifactId = addSource(client, documentId);
        JsonNode proposed = proposeUntilSettled(client, browser.workspaceId(), documentId, artifactId, RESULT_JSON);
        toolResult(client, "confirm_fill", Map.of(
                "proposal_id", proposed.get("proposal_id").asLong(),
                "payload_hash", proposed.get("payload_hash").asText()));
        JsonNode exported = toolResult(client, "export_document", Map.of("document_id", documentId, "format", "docx"));
        JsonNode link = toolResult(client, "get_export_link",
                Map.of("receipt_id", exported.get("receipt_id").asLong(), "file", "docx"));
        String path = URI.create(link.get("url").asText()).getPath();

        getAnonymous(path, 200);
        getAnonymous(path, 404);
    }

    @Test
    void answeringAnOpenQuestionHandsTheRunBack() throws Exception {
        Browser browser = signIn("subject-mcp-question", ISSUER);
        builtInTemplateProvisioningService.ensureBuiltInTemplates(browser.workspaceId(), browser.userId());
        McpSyncClient client = mcpClient(issueToken(browser, "question", "[\"read\",\"fill\",\"export\"]").token());
        long documentId = startDocumentOnMeetingMinutes(client);
        long artifactId = addSource(client, documentId);

        // The run reaches WAITING_FOR_INPUT when the worker stages a
        // question; that row is what a real extraction attempt leaves.
        Future<McpSchema.CallToolResult> pending = inBackground(
                () -> callTool(client, "propose_fill", Map.of("document_id", documentId, "source_artifact_id", artifactId)));
        long[] ids = awaitRun(browser.workspaceId(), documentId);
        long runId = ids[0];
        long jobId = ids[1];
        fabricateOpenQuestion(browser.workspaceId(), documentId, runId, jobId);

        JsonNode questions = toolResult(pending);
        assertThat(questions.get("state").asText()).isEqualTo("QUESTIONS");
        long questionId = questions.get("questions").get(0).get("question_id").asLong();
        assertThat(questions.get("questions").get(0).get("label").asText()).isNotBlank();

        JsonNode answered = toolResult(client, "answer_question",
                Map.of("question_id", questionId, "answer", "Friday"));
        // The last open question answered: the run resumes toward its next
        // stage and answers a live state, never a leftover question.
        assertThat(answered.get("state").asText()).isIn("RUNNING", "PROPOSED", "QUESTIONS");
        try (Connection connection = ownerConnection(DB);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT status FROM question WHERE id = ?")) {
            statement.setLong(1, questionId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("ANSWERED");
            }
        }
    }

    private long templateIdByName(McpSyncClient client, String name) throws Exception {
        JsonNode templates = toolResult(client, "list_templates", Map.of());
        for (JsonNode template : templates.get("templates")) {
            if (template.get("name").asText().equals(name)) {
                return template.get("template_id").asLong();
            }
        }
        throw new AssertionError("No template \"" + name + "\" in " + templates);
    }

    private long startDocumentOnMeetingMinutes(McpSyncClient client) throws Exception {
        JsonNode started = toolResult(client, "start_document",
                Map.of("template_id", templateIdByName(client, "Flowing meeting minutes")));
        return started.get("document_id").asLong();
    }

    private long addSource(McpSyncClient client, long documentId) throws Exception {
        JsonNode source = toolResult(client, "add_text_source",
                Map.of("document_id", documentId, "text", "The weekly sync moved to Thursday."));
        return source.get("source_artifact_id").asLong();
    }

    /**
     * {@code propose_fill} polls inside the call, so it runs on a spare
     * thread while the test fabricates the staged result a worker would
     * have published -- then the call answers the settled state itself.
     */
    private JsonNode proposeUntilSettled(
            McpSyncClient client, long workspaceId, long documentId, long artifactId, String resultJson) throws Exception {
        Future<McpSchema.CallToolResult> pending = inBackground(
                () -> callTool(client, "propose_fill", Map.of("document_id", documentId, "source_artifact_id", artifactId)));
        long jobId = awaitRun(workspaceId, documentId)[1];
        fabricatePublishedResult(workspaceId, jobId, resultJson);
        return toolResult(pending);
    }

    private JsonNode toolResult(Future<McpSchema.CallToolResult> pending) throws Exception {
        McpSchema.CallToolResult result = pending.get();
        if (result.structuredContent() != null) {
            return JSON.readTree(JSON.writeValueAsString(result.structuredContent()));
        }
        JsonNode first = JSON.readTree(JSON.writeValueAsString(result.content().get(0)));
        return JSON.readTree(first.get("text").asText());
    }

    private Future<McpSchema.CallToolResult> inBackground(Callable<McpSchema.CallToolResult> call) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return executor.submit(call);
    }

    /** Waits for the run {@code propose_fill} just enqueued, returning {run id, job id}. */
    private long[] awaitRun(long workspaceId, long documentId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (true) {
            try (Connection connection = ownerConnection(DB);
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT id, job_id FROM generation_run WHERE workspace_id = ? AND document_id = ? "
                                    + "ORDER BY id DESC LIMIT 1")) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, documentId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return new long[]{rs.getLong(1), rs.getLong(2)};
                    }
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("propose_fill never recorded a generation_run for document " + documentId + ".");
            }
            Thread.sleep(50);
        }
    }

    /**
     * The rows {@code worker_publish_staged_output} writes for a finished
     * extraction attempt, at the same layer the worker writes them: the
     * staged blob, its artifact, the output link and the job's new state.
     */
    private void fabricatePublishedResult(long workspaceId, long jobId, String resultJson) throws Exception {
        String objectKey = "mcp-test-result/" + UUID.randomUUID() + ".json";
        blobStore.writeNewAndDigest(objectKey, new ByteArrayInputStream(resultJson.getBytes(StandardCharsets.UTF_8)), 1_000_000);
        String sha256 = sha256Hex(resultJson);
        int byteCount = resultJson.getBytes(StandardCharsets.UTF_8).length;

        try (Connection connection = ownerConnection(DB)) {
            long stagedOutputId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO job_staged_output (workspace_id, job_id, worker_id, fencing_token, output_kind, object_key, sha256, byte_count, expires_at)
                    VALUES (?, ?, 'mcp-test-fixture', 1, 'extraction-result', ?, ?, ?, now() + interval '1 hour')
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
                statement.setString(3, objectKey);
                statement.setString(4, sha256);
                statement.setInt(5, byteCount);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    stagedOutputId = result.getLong(1);
                }
            }
            long stagedArtifactId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO artifact (workspace_id, blob_key, status, byte_count, sha256, detected_media_type, finalized_at)
                    VALUES (?, ?, 'READY', ?, ?, 'PLAIN_TEXT', now())
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setString(2, objectKey);
                statement.setInt(3, byteCount);
                statement.setString(4, sha256);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    stagedArtifactId = result.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO job_output_artifact (workspace_id, job_id, output_kind, staged_output_id, artifact_id)
                    VALUES (?, ?, 'extraction-result', ?, ?)
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
                statement.setLong(3, stagedOutputId);
                statement.setLong(4, stagedArtifactId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE job SET state = 'SUCCEEDED' WHERE id = ?")) {
                statement.setLong(1, jobId);
                statement.executeUpdate();
            }
        }
    }

    /**
     * An open question against the run plus the job state a worker leaves
     * when it is waiting on an answer.
     */
    private void fabricateOpenQuestion(long workspaceId, long documentId, long runId, long jobId) throws Exception {
        try (Connection connection = ownerConnection(DB)) {
            long fencingToken;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT fencing_token FROM job WHERE id = ?")) {
                statement.setLong(1, jobId);
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    fencingToken = rs.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO question (workspace_id, document_id, field_id, reason, candidates, generation_run_id, attempt_fencing_token)
                    VALUES (?, ?, 'meeting.date', 'MISSING_REQUIRED', '[{"value":"Thursday","evidenceSpanIds":[]},{"value":"Friday","evidenceSpanIds":[]}]'::jsonb, ?, ?)
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, documentId);
                statement.setLong(3, runId);
                statement.setLong(4, fencingToken);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE job SET state = 'WAITING_FOR_INPUT' WHERE id = ?")) {
                statement.setLong(1, jobId);
                statement.executeUpdate();
            }
        }
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
