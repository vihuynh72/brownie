package io.github.vihuynh72.brownie.api.revision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves correcting an open document's fill spots end to end, through the
 * routes, against real infrastructure: renaming and taking away a spot make
 * a new form version the document moves to with its values, a replay
 * answers with the same result, two corrections at once make one version,
 * Undo takes the document and the form back, the new version compiles and
 * validates, and the audit trail names ids and counts only. None of this
 * edits a Word file (the built-in form's spots came with it), so none of it
 * needs the Word editor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class FillSpotIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-fill-spots";

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
    private TransactionTemplate transactionTemplate;

    @Test
    void renamingASpotMakesAVersionTheDocumentMovesToWithItsValuesAndTheAuditNamesNoLabel() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-rename");
        long workspaceId = workspaceOf("subject-fill-rename");
        long documentId = createMinimalDocument(session, workspaceId);
        JsonNode before = document(session, workspaceId, documentId);
        long firstVersionId = before.get("templateVersionId").asLong();
        long firstRevisionId = before.get("currentRevision").get("id").asLong();
        assertThat(before.get("templateLatestVersionId").asLong()).isEqualTo(firstVersionId);
        assertThat(before.get("currentRevision").get("templateVersionId").asLong()).isEqualTo(firstVersionId);
        try (Connection connection = DB.superuserConnection();
             PreparedStatement keep = connection.prepareStatement(
                     "UPDATE template_version SET preparation_notices = ?::jsonb WHERE id = ?")) {
            // As if the template had been made from an upload whose notes were kept with it.
            keep.setString(1, "[{\"code\":\"PLACES_LEFT_OUT\",\"count\":2}]");
            keep.setLong(2, firstVersionId);
            assertThat(keep.executeUpdate()).isEqualTo(1);
        }

        String key = UUID.randomUUID().toString();
        String body = fillSpots(firstRevisionId, firstVersionId, "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.title\",\"label\":\"Meeting  name\"}");
        JsonNode renamed = readJson(changeFillSpots(session, workspaceId, documentId, key, body).andExpect(status().isOk()).andReturn());

        JsonNode version = renamed.get("templateVersion");
        long secondVersionId = version.get("id").asLong();
        assertThat(secondVersionId).isNotEqualTo(firstVersionId);
        assertThat(version.get("derivedFromVersionId").asLong()).isEqualTo(firstVersionId);
        assertThat(version.get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(fieldOf(version, "meeting.title").get("label").asText()).isEqualTo("Meeting name");
        assertThat(version.get("preparationNotices")).as("the upload's notes carry on to a version made from it").hasSize(1);
        assertThat(version.get("preparationNotices").get(0).get("code").asText()).isEqualTo("PLACES_LEFT_OUT");
        assertThat(version.get("preparationNotices").get(0).get("count").asInt()).isEqualTo(2);
        JsonNode revision = renamed.get("revision");
        assertThat(revision.get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(revision.get("parentRevisionId").asLong()).isEqualTo(firstRevisionId);
        assertThat(revision.get("editReason").asText()).isEqualTo("Renamed the fill spot Meeting title to Meeting name.");
        assertThat(revision.get("fields").get("meeting.title").get("value").asText()).isEqualTo("Weekly Sync");
        assertThat(revision.get("fields").get("meeting.date").get("value").asText()).isEqualTo("2026-03-12");
        assertThat(renamed.get("document").get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(renamed.get("document").get("templateLatestVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(renamed.get("previousRevisionId").asLong()).isEqualTo(firstRevisionId);
        assertThat(renamed.get("fieldIds")).hasSize(1);
        assertThat(renamed.get("fieldIds").get(0).asText()).isEqualTo("meeting.title");
        assertThat(renamed.get("otherDocumentsOnPreviousVersion").asInt()).isZero();

        JsonNode replayed = readJson(changeFillSpots(session, workspaceId, documentId, key, body).andExpect(status().isOk()).andReturn());
        assertThat(replayed.get("revision").get("id").asLong()).isEqualTo(revision.get("id").asLong());
        assertThat(replayed.get("templateVersion").get("id").asLong()).isEqualTo(secondVersionId);
        assertThat(replayed.get("fieldIds")).isEqualTo(renamed.get("fieldIds"));
        assertThat(history(session, workspaceId, documentId)).hasSize(2);

        try (Connection connection = DB.superuserConnection()) {
            List<String> audits = strings(connection,
                    "SELECT action || ' ' || resource_type || ' ' || details::text FROM audit_event WHERE workspace_id = ? ORDER BY id",
                    workspaceId);
            assertThat(audits).anyMatch(row -> row.startsWith("TEMPLATE_VERSION_DERIVED template-version")
                    && row.contains("\"versionId\": " + secondVersionId) && row.contains("\"RENAME\": 1"));
            assertThat(audits).anyMatch(row -> row.startsWith("DOCUMENT_TEMPLATE_VERSION_CHANGED document")
                    && row.contains("\"toVersionId\": " + secondVersionId));
            assertThat(audits).noneMatch(row -> row.contains("Meeting") || row.contains("Weekly"));
            // A rename edits nothing in the file, so the form's proven render is the same one.
            List<String> baselineFiles = strings(connection,
                    "SELECT docx_artifact_id::text FROM template_baseline_render WHERE template_version_id IN (?, ?) ORDER BY template_version_id",
                    firstVersionId, secondVersionId);
            assertThat(baselineFiles).hasSize(2);
            assertThat(baselineFiles.get(1)).isEqualTo(baselineFiles.get(0));
        }
    }

    @Test
    void removingASpotDropsItsValueCompilesAndValidatesOnTheNewVersionAndUndoTakesTheFormBackToo() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-remove");
        long workspaceId = workspaceOf("subject-fill-remove");
        long documentId = createMinimalDocument(session, workspaceId);
        JsonNode before = document(session, workspaceId, documentId);
        long firstVersionId = before.get("templateVersionId").asLong();
        long firstRevisionId = before.get("currentRevision").get("id").asLong();
        long templateId = before.get("templateId").asLong();

        JsonNode removed = readJson(changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(firstRevisionId, firstVersionId, "{\"kind\":\"REMOVE\",\"fieldId\":\"meeting.date\"}"))
                .andExpect(status().isOk())
                .andReturn());
        long secondVersionId = removed.get("templateVersion").get("id").asLong();
        long removedRevisionId = removed.get("revision").get("id").asLong();
        assertThat(removed.get("revision").get("fields").has("meeting.date")).isFalse();
        assertThat(removed.get("revision").get("editReason").asText()).isEqualTo("Removed the fill spot Meeting date.");
        assertThat(fieldOf(removed.get("templateVersion"), "meeting.date")).isNull();
        assertThat(currentActiveVersionOf(session, workspaceId, templateId)).isEqualTo(secondVersionId);

        JsonNode compiled = readJson(mockMvc.perform(post(documentPath(workspaceId, documentId) + "/revisions/" + removedRevisionId + "/compile")
                        .cookie(session).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(compiled.get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(compiled.get("allIntegrityChecksPassed").asBoolean()).isTrue();
        JsonNode validated = readJson(mockMvc.perform(post(documentPath(workspaceId, documentId) + "/validate")
                        .cookie(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + removedRevisionId + "}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn());
        assertThat(validated.get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        long afterValidationRevisionId = currentRevisionId(session, workspaceId, documentId);

        JsonNode undone = readJson(restore(session, workspaceId, documentId, firstRevisionId, afterValidationRevisionId)
                .andExpect(status().isOk())
                .andReturn());
        assertThat(undone.get("revision").get("templateVersionId").asLong()).isEqualTo(firstVersionId);
        assertThat(undone.get("revision").get("fields").get("meeting.date").get("value").asText()).isEqualTo("2026-03-12");
        assertThat(undone.get("droppedFieldIds")).isEmpty();
        assertThat(document(session, workspaceId, documentId).get("templateVersionId").asLong()).isEqualTo(firstVersionId);
        // The document was the only one on the version it left, one correction away, so the form went back with it.
        assertThat(currentActiveVersionOf(session, workspaceId, templateId)).isEqualTo(firstVersionId);

        JsonNode redone = readJson(restore(session, workspaceId, documentId, removedRevisionId, undone.get("revision").get("id").asLong())
                .andExpect(status().isOk())
                .andReturn());
        assertThat(redone.get("revision").get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(currentActiveVersionOf(session, workspaceId, templateId)).isEqualTo(secondVersionId);
    }

    @Test
    void theSameCorrectionAfterAnUndoTakesTheFormOnAgainWithTheDocument() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-again");
        long workspaceId = workspaceOf("subject-fill-again");
        long documentId = createMinimalDocument(session, workspaceId);
        long otherDocumentId = createMinimalDocument(session, workspaceId);
        JsonNode before = document(session, workspaceId, documentId);
        long firstVersionId = before.get("templateVersionId").asLong();
        long firstRevisionId = before.get("currentRevision").get("id").asLong();
        long templateId = before.get("templateId").asLong();
        String remove = "{\"kind\":\"REMOVE\",\"fieldId\":\"meeting.date\"}";

        JsonNode removed = readJson(changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(firstRevisionId, firstVersionId, remove))
                .andExpect(status().isOk())
                .andReturn());
        long secondVersionId = removed.get("templateVersion").get("id").asLong();
        long undoneRevisionId = readJson(restore(session, workspaceId, documentId, firstRevisionId, removed.get("revision").get("id").asLong())
                .andExpect(status().isOk())
                .andReturn()).get("revision").get("id").asLong();
        assertThat(currentActiveVersionOf(session, workspaceId, templateId)).isEqualTo(firstVersionId);

        JsonNode again = readJson(changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(undoneRevisionId, firstVersionId, remove))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(again.get("templateVersion").get("id").asLong()).isEqualTo(secondVersionId);
        assertThat(again.get("document").get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(again.get("document").get("templateLatestVersionId").asLong()).isEqualTo(secondVersionId);
        assertThat(currentActiveVersionOf(session, workspaceId, templateId)).isEqualTo(secondVersionId);
        // The next correction is taken; after it, the same change asked for on the first version is no longer the form's line.
        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(), fillSpots(
                        again.get("revision").get("id").asLong(), secondVersionId,
                        "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.title\",\"label\":\"Meeting name\"}"))
                .andExpect(status().isOk());
        JsonNode other = document(session, workspaceId, otherDocumentId);
        changeFillSpots(session, workspaceId, otherDocumentId, UUID.randomUUID().toString(),
                        fillSpots(other.get("currentRevision").get("id").asLong(), firstVersionId, remove))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_TEMPLATE_VERSION_MOVED"));
    }

    /**
     * While another document is still being moved onto the version an Undo
     * would take the form off, the Undo waits for it and then leaves the form
     * where that document is; the same move sent again meanwhile waits too,
     * answers with the first one's revision, and the move is recorded once.
     * The held move uses its own sign-in so the waiting is for the form, not
     * for a shared session row.
     */
    @Test
    void anUndoAndTheSameMoveSentAgainWaitForAMoveStillBeingWritten() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-waits");
        Cookie heldSession = loginAndGetSessionCookie("subject-fill-waits");
        long workspaceId = workspaceOf("subject-fill-waits");
        long documentId = createMinimalDocument(session, workspaceId);
        long otherDocumentId = createMinimalDocument(session, workspaceId);
        JsonNode before = document(session, workspaceId, documentId);
        long firstVersionId = before.get("templateVersionId").asLong();
        long firstRevisionId = before.get("currentRevision").get("id").asLong();
        long templateId = before.get("templateId").asLong();
        JsonNode removed = readJson(changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(firstRevisionId, firstVersionId, "{\"kind\":\"REMOVE\",\"fieldId\":\"meeting.date\"}"))
                .andExpect(status().isOk())
                .andReturn());
        long secondVersionId = removed.get("templateVersion").get("id").asLong();
        long removedRevisionId = removed.get("revision").get("id").asLong();
        String key = UUID.randomUUID().toString();
        String body = "{\"expectedRevisionId\":" + currentRevisionId(session, workspaceId, otherDocumentId)
                + ",\"templateVersionId\":" + secondVersionId + "}";

        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> held = pool.submit(() -> transactionTemplate.execute(status -> {
                try {
                    int code = move(heldSession, workspaceId, otherDocumentId, key, body).andReturn().getResponse().getStatus();
                    written.countDown();
                    release.await();
                    return code;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(written.await(60, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> undo = pool.submit(() -> restore(session, workspaceId, documentId, firstRevisionId, removedRevisionId).andReturn());
            Future<MvcResult> resent = pool.submit(() -> move(session, workspaceId, otherDocumentId, key, body).andReturn());
            assertThatThrownBy(() -> undo.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(resent.isDone()).isFalse();
            release.countDown();

            assertThat(held.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            MvcResult undone = undo.get(60, TimeUnit.SECONDS);
            assertThat(undone.getResponse().getStatus()).isEqualTo(200);
            assertThat(readJson(undone).get("revision").get("templateVersionId").asLong()).isEqualTo(firstVersionId);
            MvcResult again = resent.get(60, TimeUnit.SECONDS);
            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(readJson(again).get("document").get("templateVersionId").asLong()).isEqualTo(secondVersionId);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // The other document is on the second version, so the form stayed there.
        assertThat(currentActiveVersionOf(session, workspaceId, templateId)).isEqualTo(secondVersionId);
        assertThat(history(session, workspaceId, otherDocumentId)).hasSize(2);
        try (Connection connection = DB.superuserConnection()) {
            List<String> moves = strings(connection,
                    "SELECT details::text FROM audit_event WHERE workspace_id = ? AND action = 'DOCUMENT_TEMPLATE_VERSION_CHANGED'"
                            + " AND resource_id = ?",
                    workspaceId, otherDocumentId);
            assertThat(moves).singleElement().satisfies(row -> assertThat(row).contains("\"toVersionId\": " + secondVersionId));
        }
    }

    @Test
    void aLockedValueIsNotRemovedAndStaleOrMismatchedOrMalformedRequestsChangeNothing() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-refused");
        long workspaceId = workspaceOf("subject-fill-refused");
        long documentId = createMinimalDocument(session, workspaceId);
        JsonNode before = document(session, workspaceId, documentId);
        long versionId = before.get("templateVersionId").asLong();
        long firstRevisionId = before.get("currentRevision").get("id").asLong();
        long lockedRevisionId = readJson(mockMvc.perform(post(documentPath(workspaceId, documentId) + "/fields/lock")
                        .cookie(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + firstRevisionId + ",\"fieldId\":\"meeting.date\",\"lock\":\"EXPLICITLY_LOCKED\"}"))
                .andExpect(status().isOk())
                .andReturn()).get("id").asLong();

        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(lockedRevisionId, versionId, "{\"kind\":\"REMOVE\",\"fieldId\":\"meeting.date\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILL_SPOT_LOCKED"))
                .andExpect(jsonPath("$.fieldId").value("meeting.date"));
        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(firstRevisionId, versionId, "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.title\",\"label\":\"Name\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"));
        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(lockedRevisionId, versionId + 1000, "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.title\",\"label\":\"Name\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_TEMPLATE_VERSION_MOVED"))
                .andExpect(jsonPath("$.templateLatestVersionId").value(versionId));
        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(lockedRevisionId, versionId, "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.title\",\"label\":\"Meeting date\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILL_SPOT_CHANGE_INVALID"));
        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(lockedRevisionId, versionId, "{\"kind\":\"GROW\",\"fieldId\":\"meeting.title\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(history(session, workspaceId, documentId)).hasSize(2);
        assertThat(document(session, workspaceId, documentId).get("templateLatestVersionId").asLong()).isEqualTo(versionId);
    }

    @Test
    void twoCorrectionsAtOnceMakeOneVersionAndTheOtherDocumentMovesToItKeepingItsValues() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-race");
        long workspaceId = workspaceOf("subject-fill-race");
        long firstDocumentId = createMinimalDocument(session, workspaceId);
        long secondDocumentId = createMinimalDocument(session, workspaceId);
        JsonNode first = document(session, workspaceId, firstDocumentId);
        JsonNode second = document(session, workspaceId, secondDocumentId);
        long versionId = first.get("templateVersionId").asLong();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            results.add(pool.submit(racing(start, session, workspaceId, firstDocumentId, fillSpots(
                    first.get("currentRevision").get("id").asLong(), versionId,
                    "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.location\",\"label\":\"Venue\"}"))));
            results.add(pool.submit(racing(start, session, workspaceId, secondDocumentId, fillSpots(
                    second.get("currentRevision").get("id").asLong(), versionId,
                    "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.organization\",\"label\":\"Club\"}"))));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            for (Future<MvcResult> result : results) {
                MvcResult done = result.get();
                statuses.add(done.getResponse().getStatus());
                if (done.getResponse().getStatus() == 409) {
                    codes.add(readJson(done).get("code").asText());
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(codes).singleElement().isIn("TEMPLATE_VERSION_MOVED_ON", "DOCUMENT_TEMPLATE_VERSION_MOVED");
        } finally {
            pool.shutdownNow();
        }

        long winner = document(session, workspaceId, firstDocumentId).get("templateVersionId").asLong() != versionId
                ? firstDocumentId
                : secondDocumentId;
        long other = winner == firstDocumentId ? secondDocumentId : firstDocumentId;
        JsonNode behind = document(session, workspaceId, other);
        long latestVersionId = behind.get("templateLatestVersionId").asLong();
        assertThat(behind.get("templateVersionId").asLong()).isEqualTo(versionId);
        assertThat(latestVersionId).isNotEqualTo(versionId);

        String key = UUID.randomUUID().toString();
        String body = "{\"expectedRevisionId\":" + behind.get("currentRevision").get("id").asLong() + ",\"templateVersionId\":" + latestVersionId + "}";
        JsonNode moved = readJson(move(session, workspaceId, other, key, body).andExpect(status().isOk()).andReturn());
        assertThat(moved.get("document").get("templateVersionId").asLong()).isEqualTo(latestVersionId);
        assertThat(moved.get("previousTemplateVersionId").asLong()).isEqualTo(versionId);
        assertThat(moved.get("droppedFieldIds")).isEmpty();
        assertThat(moved.get("revision").get("fields").get("meeting.title").get("value").asText()).isEqualTo("Weekly Sync");
        assertThat(moved.get("revision").get("editReason").asText()).startsWith("Moved to version ");
        JsonNode replayed = readJson(move(session, workspaceId, other, key, body).andExpect(status().isOk()).andReturn());
        assertThat(replayed.get("revision").get("id").asLong()).isEqualTo(moved.get("revision").get("id").asLong());
        move(session, workspaceId, other, UUID.randomUUID().toString(),
                        "{\"expectedRevisionId\":" + moved.get("revision").get("id").asLong() + ",\"templateVersionId\":" + latestVersionId + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_ON_VERSION"));
    }

    /** A version made from another points back at it and revisions point at their versions; deleting everything must still work. */
    @Test
    void aWorkspaceWithMadeVersionsAndDocumentsOnThemCanStillBeDeleted() throws Exception {
        Cookie session = signInWithBuiltIns("subject-fill-delete");
        long workspaceId = workspaceOf("subject-fill-delete");
        long documentId = createMinimalDocument(session, workspaceId);
        JsonNode before = document(session, workspaceId, documentId);
        long versionId = before.get("templateVersionId").asLong();
        JsonNode renamed = readJson(changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(before.get("currentRevision").get("id").asLong(), versionId,
                                "{\"kind\":\"RENAME\",\"fieldId\":\"meeting.title\",\"label\":\"Meeting name\"}"))
                .andExpect(status().isOk())
                .andReturn());
        changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(),
                        fillSpots(renamed.get("revision").get("id").asLong(), renamed.get("templateVersion").get("id").asLong(),
                                "{\"kind\":\"REMOVE\",\"fieldId\":\"meeting.location\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/deletions")
                        .cookie(session).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk());

        try (Connection connection = DB.superuserConnection()) {
            assertThat(strings(connection, "SELECT id::text FROM template_version WHERE workspace_id = ?", workspaceId)).isEmpty();
            assertThat(strings(connection, "SELECT id::text FROM document_revision WHERE workspace_id = ?", workspaceId)).isEmpty();
            assertThat(strings(connection, "SELECT id::text FROM workspace WHERE id = ?", workspaceId)).isEmpty();
        }
    }

    private Callable<MvcResult> racing(CountDownLatch start, Cookie session, long workspaceId, long documentId, String body) {
        return () -> {
            start.await();
            return changeFillSpots(session, workspaceId, documentId, UUID.randomUUID().toString(), body).andReturn();
        };
    }

    private static String fillSpots(long expectedRevisionId, long templateVersionId, String change) {
        return "{\"expectedRevisionId\":" + expectedRevisionId + ",\"templateVersionId\":" + templateVersionId + ",\"changes\":[" + change + "]}";
    }

    private ResultActions changeFillSpots(Cookie session, long workspaceId, long documentId, String key, String body) throws Exception {
        return mockMvc.perform(post(documentPath(workspaceId, documentId) + "/fill-spots")
                .cookie(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private ResultActions move(Cookie session, long workspaceId, long documentId, String key, String body) throws Exception {
        return mockMvc.perform(post(documentPath(workspaceId, documentId) + "/template-version")
                .cookie(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private ResultActions restore(Cookie session, long workspaceId, long documentId, long revisionId, long expectedRevisionId) throws Exception {
        return mockMvc.perform(post(documentPath(workspaceId, documentId) + "/revisions/" + revisionId + "/restore")
                .cookie(session)
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"expectedRevisionId\":" + expectedRevisionId + "}"));
    }

    private static JsonNode fieldOf(JsonNode version, String fieldId) {
        for (JsonNode field : version.get("fields")) {
            if (field.get("fieldId").asText().equals(fieldId)) {
                return field;
            }
        }
        return null;
    }

    private long currentActiveVersionOf(Cookie session, long workspaceId, long templateId) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        for (JsonNode template : templates) {
            if (template.get("id").asLong() == templateId) {
                return template.get("currentActiveVersionId").asLong();
            }
        }
        throw new AssertionError("Template " + templateId + " is not listed: " + templates);
    }

    private JsonNode document(Cookie session, long workspaceId, long documentId) throws Exception {
        return readJson(mockMvc.perform(get(documentPath(workspaceId, documentId)).cookie(session)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode history(Cookie session, long workspaceId, long documentId) throws Exception {
        return readJson(mockMvc.perform(get(documentPath(workspaceId, documentId) + "/revisions").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
    }

    private long currentRevisionId(Cookie session, long workspaceId, long documentId) throws Exception {
        return document(session, workspaceId, documentId).get("currentRevision").get("id").asLong();
    }

    private static List<String> strings(Connection connection, String sql, long... parameters) throws Exception {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setLong(i + 1, parameters[i]);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    values.add(results.getString(1));
                }
            }
        }
        return values;
    }

    private long createMinimalDocument(Cookie session, long workspaceId) throws Exception {
        long[] template = findFlowingTemplateAndActiveVersion(session, workspaceId);
        String body = "{"
                + "\"title\":\"Weekly Sync\","
                + "\"templateId\":" + template[0] + ","
                + "\"templateVersionId\":" + template[1] + ","
                + "\"fields\":{\"meeting.title\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Weekly Sync\"},"
                + "\"meeting.date\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2026-03-12\"}},"
                + "\"initialRevisionReason\":\"Created for a fill spot test.\"}";
        return readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asLong();
    }

    private long[] findFlowingTemplateAndActiveVersion(Cookie session, long workspaceId) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + workspaceId + "/templates").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        for (JsonNode template : templates) {
            if (template.get("displayName").asText().equals("Flowing meeting minutes")) {
                return new long[] {template.get("id").asLong(), template.get("currentActiveVersionId").asLong()};
            }
        }
        throw new AssertionError("Flowing meeting minutes template was not provisioned: " + templates);
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

    private static String documentPath(long workspaceId, long documentId) {
        return "/api/v1/workspaces/" + workspaceId + "/documents/" + documentId;
    }

    private static JsonNode readJson(MvcResult result) throws Exception {
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
        return new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String subject) {
        var identity = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id());
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }
}
