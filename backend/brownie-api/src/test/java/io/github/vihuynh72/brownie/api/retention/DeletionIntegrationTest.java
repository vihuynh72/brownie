package io.github.vihuynh72.brownie.api.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.generation.GenerationJobTypes;
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
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deletion is only worth having if it is complete, so these tests look at
 * the database and the blob store directly, as the table owner, rather
 * than trusting what the routes say about themselves: after a document is
 * deleted for good, no row that names it may remain in any table, every
 * stored object it owned must be queued for removal under the exact key
 * the application stores it at, and something another document still uses
 * must still be there. Trash, by contrast, must lose nothing. Proven
 * through real HTTP against real Postgres, Azurite and ClamAV, with the
 * real renderer producing the compiled and validated files that then have
 * to be found again and removed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class DeletionIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-deletion";

    /** Every table that carries a document id, which is exactly what must be empty for a document that was deleted for good. */
    private static final List<String> DOCUMENT_TABLES = List.of(
            "document_revision", "document_revision_field_evidence", "document_revision_field_state",
            "document_command_receipt", "document_compilation", "document_patch_proposal", "document_source",
            "question", "generation_run", "validation_manifest", "export_approval", "export_receipt");

    /** Every table that carries a workspace id and must hold nothing of a deleted workspace. The two ledger tables are deliberately absent. */
    private static final List<String> WORKSPACE_TABLES = List.of(
            "workspace_member", "artifact", "extraction_version", "pdf_extraction_version",
            "plain_text_extraction_version", "source_snapshot", "source_span", "template", "template_version",
            "rule_revision", "template_example", "rule_revision_proposal_evidence", "template_baseline_render",
            "job", "idempotency_record", "command_receipt", "job_event", "outbox_event", "job_staged_output",
            "job_output_artifact", "document", "document_revision", "document_command_receipt",
            "document_revision_field_evidence", "document_compilation", "question", "document_revision_field_state",
            "document_patch_proposal", "document_patch_proposal_evidence", "validation_manifest", "export_approval",
            "export_receipt", "document_source", "generation_run", "connector_connection", "connector_resource_grant");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("clamav/clamav-debian:1.4"))
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started\\.\\n", 1))
            .withStartupTimeout(java.time.Duration.ofMinutes(3));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", AZURITE::getConnectionString);
        registry.add("brownie.security.clamav.host", CLAMAV::getHost);
        registry.add("brownie.security.clamav.port", () -> CLAMAV.getMappedPort(3310));
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
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
    private BlobStore blobStore;

    @Autowired
    private javax.sql.DataSource apiDataSource;

    @Test
    void aTrashedDocumentAnswersNowhereRefusesEveryChangeAndComesBackExactlyAsItWas() throws Exception {
        Owner owner = signIn("subject-trash-owner");
        long documentId = createDocument(owner, "Budget minutes");
        String document = documentsPath(owner) + "/" + documentId;
        // An edit that succeeds now, so that the very same request can be replayed once the document is in the trash.
        String replayedKey = UUID.randomUUID().toString();
        String replayedBody = editBody(currentRevisionId(owner, documentId), "Budget minutes, corrected");
        mockMvc.perform(patch(document + "/content")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", replayedKey)
                        .contentType("application/json").content(replayedBody))
                .andExpect(status().isOk());
        long revisionId = currentRevisionId(owner, documentId);
        // A proposal waiting to be accepted: found by its own id, like a question.
        long proposalId = readJson(mockMvc.perform(post(document + "/assist/execute")
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"text\":\"change the meeting title to Spring Budget\",\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isOk()).andReturn()).get("proposal").get("id").asLong();
        // A question is answered through its own id, not through its document, so it needs its own proof.
        long questionId = insertOpenQuestionAsOwner(owner.workspaceId(), documentId);
        String answerPath = "/api/v1/workspaces/" + owner.workspaceId() + "/questions/" + questionId + "/answer";

        JsonNode trashed = readJson(mockMvc.perform(post(deletionsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}"))
                .andExpect(status().isCreated())
                .andReturn());
        long deletionId = trashed.get("id").asLong();
        assertThat(trashed.get("state").asText()).isEqualTo("TRASHED");
        assertThat(trashed.get("title").asText()).isEqualTo("Budget minutes");
        assertThat(trashed.get("purgeAfter").asText()).isNotBlank();

        // Trashing it again is the same entry, not a second one.
        mockMvc.perform(post(deletionsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(deletionId));

        // It is gone from the list and from every route that reads it or anything derived from it.
        JsonNode list = readJson(mockMvc.perform(get(documentsPath(owner)).cookie(owner.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).noneMatch(node -> node.get("id").asLong() == documentId);
        for (String path : List.of(
                document,
                document + "/revisions",
                document + "/revisions/" + revisionId,
                document + "/revisions/" + revisionId + "/compilation",
                document + "/revisions/" + revisionId + "/validation",
                document + "/sources",
                document + "/generations",
                document + "/export-approval",
                document + "/export-receipt")) {
            mockMvc.perform(get(path).cookie(owner.session())).andExpect(status().isNotFound());
        }

        // Nothing can change it, including a request built from the revision it still has.
        mockMvc.perform(patch(document + "/content")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(editBody(revisionId, "Edited while in the trash")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(document + "/validate")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(answerPath)
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"answerValue\":\"Answered while in the trash\"}"))
                .andExpect(status().isNotFound());
        // Not found, rather than a fault halfway through comparing it with a document that answers nowhere.
        mockMvc.perform(post(document + "/patch-proposals/" + proposalId + "/accept")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isNotFound());
        // The request that succeeded before the trash, sent again under the same key: also not found.
        mockMvc.perform(patch(document + "/content")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", replayedKey)
                        .contentType("application/json").content(replayedBody))
                .andExpect(status().isNotFound());
        assertThat(countAsOwner("SELECT count(*) FROM document_revision WHERE document_id = ?", documentId)).isEqualTo(2);
        assertThat(stringAsOwner("SELECT status FROM question WHERE id = ?", questionId)).isEqualTo("OPEN");

        // The trash lists it by title while it is there.
        JsonNode entries = readJson(mockMvc.perform(get(deletionsPath(owner)).cookie(owner.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(entries).anyMatch(node -> node.get("id").asLong() == deletionId
                && node.get("title").asText().equals("Budget minutes"));

        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/restore").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RESTORED"));
        // Restoring twice is the same answer.
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/restore").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RESTORED"));

        assertThat(currentRevisionId(owner, documentId)).isEqualTo(revisionId);
        assertThat(stringsAsOwner(
                        "SELECT action FROM audit_event WHERE resource_type = 'document' AND resource_id = ? ORDER BY id", documentId))
                .containsExactly("DOCUMENT_TRASHED", "DOCUMENT_RESTORED");
        mockMvc.perform(patch(document + "/content")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(editBody(revisionId, "Edited after coming back")))
                .andExpect(status().isOk());

        mockMvc.perform(post(answerPath)
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"answerValue\":\"Answered after coming back\"}"))
                .andExpect(status().isOk());

        // What was restored can no longer be deleted through that entry.
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_NOT_OPEN"));
    }

    @Test
    void deletingForGoodRemovesEveryRowQueuesEveryOwnedObjectAndLeavesOnlyTheLedger() throws Exception {
        Owner owner = signIn("subject-purge-owner");
        long documentId = createDocument(owner, "Minutes to delete");
        long revisionId = currentRevisionId(owner, documentId);
        String document = documentsPath(owner) + "/" + documentId;

        long sourceArtifactId = uploadAndFinalize(owner, "The meeting was called to order by Priya Rao on March 5.");
        JsonNode run = readJson(mockMvc.perform(post(document + "/generations")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isAccepted()).andReturn());
        long jobId = run.get("jobId").asLong();
        String bundleHash = stringAsOwner("SELECT bundle_hash FROM generation_run WHERE job_id = ?", jobId);

        JsonNode compilation = readJson(mockMvc.perform(post(document + "/revisions/" + revisionId + "/compile")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        JsonNode manifest = readJson(mockMvc.perform(post(document + "/validate")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + revisionId + "}"))
                .andExpect(status().isCreated()).andReturn());
        long validatedRevisionId = currentRevisionId(owner, documentId);
        mockMvc.perform(post(document + "/export-approval")
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"validationManifestId\":" + manifest.get("id").asLong() + ",\"format\":\"BOTH\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(document + "/export").cookie(owner.session()).with(csrf())).andExpect(status().isCreated());
        assertThat(validatedRevisionId).isPositive();

        List<Long> ownedArtifactIds = List.of(
                sourceArtifactId,
                compilation.get("docxArtifactId").asLong(),
                compilation.get("pdfArtifactId").asLong(),
                manifest.get("docxArtifactId").asLong(),
                manifest.get("pdfArtifactId").asLong());
        List<String> expectedKeys = new ArrayList<>();
        for (long artifactId : ownedArtifactIds) {
            expectedKeys.add(stringAsOwner("SELECT blob_key FROM artifact WHERE id = ?", artifactId));
        }
        // The three objects no column records, under the exact keys the application writes them at.
        expectedKeys.add(GenerationJobTypes.inputBundleObjectKey(owner.workspaceId(), bundleHash));
        expectedKeys.add(GenerationJobTypes.pendingQuestionsObjectKey(owner.workspaceId(), jobId));
        expectedKeys.add(GenerationJobTypes.resolvedAnswersObjectKey(owner.workspaceId(), jobId));
        assertThat(blobStore.sizeOf(GenerationJobTypes.inputBundleObjectKey(owner.workspaceId(), bundleHash))).isPresent();
        long templateArtifactsBefore = countAsOwner(
                "SELECT count(*) FROM artifact WHERE workspace_id = ?", owner.workspaceId()) - ownedArtifactIds.size();

        long deletionId = trash(owner, documentId);
        // The queued run was cancelled the moment its document went to the trash.
        assertThat(stringAsOwner("SELECT state FROM job WHERE id = ?", jobId)).isEqualTo("CANCELLED");

        JsonNode purged = readJson(mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(purged.get("state").asText()).isEqualTo("PURGED");
        assertThat(purged.get("title").isNull()).isTrue();
        assertThat(purged.get("pendingObjectCount").asInt()).isEqualTo(expectedKeys.size());
        // Deleting it again is the same answer, and queues nothing twice.
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingObjectCount").value(expectedKeys.size()));

        assertThat(countAsOwner("SELECT count(*) FROM document WHERE id = ?", documentId)).isZero();
        for (String table : DOCUMENT_TABLES) {
            assertThat(countAsOwner("SELECT count(*) FROM " + table + " WHERE document_id = ?", documentId))
                    .as("rows left in %s", table)
                    .isZero();
        }
        assertThat(countAsOwner("SELECT count(*) FROM job WHERE id = ?", jobId)).isZero();
        assertThat(countAsOwner("SELECT count(*) FROM job_event WHERE job_id = ?", jobId)).isZero();
        assertThat(countAsOwner("SELECT count(*) FROM outbox_event WHERE job_id = ?", jobId)).isZero();
        assertThat(countAsOwner("SELECT count(*) FROM command_receipt WHERE job_id = ?", jobId)).isZero();
        for (long artifactId : ownedArtifactIds) {
            assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE id = ?", artifactId)).as("artifact %d", artifactId).isZero();
        }
        assertThat(countAsOwner("SELECT count(*) FROM plain_text_extraction_version WHERE artifact_id = ?", sourceArtifactId)).isZero();
        assertThat(countAsOwner("SELECT count(*) FROM source_snapshot WHERE artifact_id = ?", sourceArtifactId)).isZero();
        // What the document never owned is untouched: the workspace's templates and their files.
        assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE workspace_id = ?", owner.workspaceId()))
                .isEqualTo(templateArtifactsBefore);

        assertThat(stringsAsOwner(
                        "SELECT object_key FROM deletion_blob_task WHERE deletion_request_id = ? AND state = 'PENDING'", deletionId))
                .containsExactlyInAnyOrderElementsOf(expectedKeys);
        // The ledger holds ids and counts, never what the person wrote.
        String inventory = stringAsOwner("SELECT inventory::text FROM deletion_request WHERE id = ?", deletionId);
        assertThat(inventory).contains("rowsRemoved").doesNotContain("Minutes to delete");

        // Each step was recorded as it happened: who, what, under which request, and no content. The record of
        // the export made earlier in this test outlives the document too, which is the point of keeping one.
        List<String> audit = stringsAsOwner("""
                SELECT action || '|' || actor_user_id || '|' || resource_type || '|' || (correlation_id IS NOT NULL) || '|' || details::text
                FROM audit_event WHERE resource_type = 'document' AND resource_id = ? ORDER BY id
                """, documentId);
        assertThat(audit).hasSize(3);
        assertThat(audit.get(0)).startsWith("DOCUMENT_EXPORTED|" + owner.userId() + "|document|true|").contains("exportReceiptId");
        assertThat(audit.get(1)).startsWith("DOCUMENT_TRASHED|" + owner.userId() + "|document|true|").contains("retentionDays");
        assertThat(audit.get(2)).startsWith("DOCUMENT_DELETED|" + owner.userId() + "|document|true|")
                .contains("rowsRemoved").contains("\"deletionRequestId\": " + deletionId);
        assertThat(String.join(" ", audit)).doesNotContain("Minutes to delete").doesNotContain("Priya");

        mockMvc.perform(get(document).cookie(owner.session())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/workspaces/" + owner.workspaceId() + "/uploads/" + sourceArtifactId + "/download")
                        .cookie(owner.session()))
                .andExpect(status().isNotFound());
        // Deleted for good is not restorable.
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/restore").cookie(owner.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_NOT_OPEN"));
    }

    @Test
    void aSourceAnotherDocumentStillUsesSurvivesAndGoesWithTheLastDocumentThatLinksIt() throws Exception {
        Owner owner = signIn("subject-shared-source");
        long first = createDocument(owner, "First minutes");
        long second = createDocument(owner, "Second minutes");
        long sourceArtifactId = uploadAndFinalize(owner, "Shared notes for two meetings.");
        for (long documentId : List.of(first, second)) {
            mockMvc.perform(post(documentsPath(owner) + "/" + documentId + "/sources")
                            .cookie(owner.session()).with(csrf())
                            .contentType("application/json").content("{\"artifactId\":" + sourceArtifactId + "}"))
                    .andExpect(status().isCreated());
        }

        long firstDeletion = trash(owner, first);
        mockMvc.perform(post(deletionsPath(owner) + "/" + firstDeletion + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingObjectCount").value(0));
        assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE id = ?", sourceArtifactId)).isEqualTo(1);
        mockMvc.perform(get(documentsPath(owner) + "/" + second + "/sources").cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        long secondDeletion = trash(owner, second);
        mockMvc.perform(post(deletionsPath(owner) + "/" + secondDeletion + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingObjectCount").value(1));
        assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE id = ?", sourceArtifactId)).isZero();
    }

    @Test
    void aWorkerStillHoldingTheDocumentsWorkDelaysDeletionWithoutLosingTheRequest() throws Exception {
        Owner owner = signIn("subject-running-work");
        long documentId = createDocument(owner, "Minutes with a running job");
        long sourceArtifactId = uploadAndFinalize(owner, "Notes that a worker is reading right now.");
        long jobId = readJson(mockMvc.perform(post(documentsPath(owner) + "/" + documentId + "/generations")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isAccepted()).andReturn()).get("jobId").asLong();
        // A worker claims it: a live lease, exactly as the claim routine leaves a job.
        updateAsOwner("""
                UPDATE job SET state = 'LEASED', lease_owner = 'worker-under-test', fencing_token = 1, attempt_count = 1,
                               lease_expires_at = now() + interval '2 minutes'
                WHERE id = ?
                """, jobId);

        long deletionId = trash(owner, documentId);
        assertThat(stringAsOwner("SELECT state FROM job WHERE id = ?", jobId)).isEqualTo("CANCEL_REQUESTED");

        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_WAITING_FOR_RUNNING_WORK"));
        assertThat(countAsOwner("SELECT count(*) FROM document WHERE id = ?", documentId)).isEqualTo(1);
        mockMvc.perform(get(deletionsPath(owner) + "/" + deletionId).cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TRASHED"));

        // The worker dies without letting go; once its lease has run out nothing holds the rows any more.
        updateAsOwner("UPDATE job SET lease_expires_at = now() - interval '1 second' WHERE id = ?", jobId);
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PURGED"));
        assertThat(countAsOwner("SELECT count(*) FROM job WHERE id = ?", jobId)).isZero();
    }

    /**
     * Any ready file can be attached as a source, including one another
     * document generated. Deleting a document must neither fail because a
     * file it links belongs to someone else's compilation, nor take away the
     * bytes of its own published result while another document still reads it.
     */
    @Test
    void aFileSomethingElseStillUsesSurvivesTheDocumentThatLinkedOrProducedIt() throws Exception {
        Owner owner = signIn("subject-files-in-use");
        long doomed = createDocument(owner, "Doomed minutes");
        long keeper = createDocument(owner, "Keeper minutes");
        String keeperPath = documentsPath(owner) + "/" + keeper;

        // The keeper's compiled file, linked to the doomed document as a source.
        long keeperRevisionId = currentRevisionId(owner, keeper);
        long keeperDocxId = readJson(mockMvc.perform(post(keeperPath + "/revisions/" + keeperRevisionId + "/compile")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isCreated()).andReturn()).get("docxArtifactId").asLong();
        attachSource(owner, doomed, keeperDocxId);

        // The doomed document's own published result, linked to the keeper as a source.
        long notesId = uploadAndFinalize(owner, "Notes the doomed document's run reads.");
        long jobId = readJson(mockMvc.perform(post(documentsPath(owner) + "/" + doomed + "/generations")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + notesId + "}"))
                .andExpect(status().isAccepted()).andReturn()).get("jobId").asLong();
        String resultKey = "temporary/workspace-" + owner.workspaceId() + "/job-" + jobId + "/attempt-1/extraction-result";
        long resultArtifactId = publishResultAsOwner(owner.workspaceId(), jobId, resultKey);
        attachSource(owner, keeper, resultArtifactId);
        // The result is read through the run, so it answers only under the document the run belongs to.
        mockMvc.perform(get(documentsPath(owner) + "/" + doomed + "/generations/" + jobId + "/result").cookie(owner.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get(keeperPath + "/generations/" + jobId + "/result").cookie(owner.session()))
                .andExpect(status().isNotFound());

        long deletionId = trash(owner, doomed);
        mockMvc.perform(get(documentsPath(owner) + "/" + doomed + "/generations/" + jobId + "/result").cookie(owner.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PURGED"));

        // Both files are still on record and still readable.
        assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE id = ?", keeperDocxId)).isEqualTo(1);
        assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE id = ?", resultArtifactId)).isEqualTo(1);
        mockMvc.perform(get(keeperPath + "/revisions/" + keeperRevisionId + "/compilation").cookie(owner.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get(keeperPath + "/sources").cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // A published result lives under its staged object's key; that key must not have been queued.
        List<String> queued = stringsAsOwner("SELECT object_key FROM deletion_blob_task WHERE deletion_request_id = ?", deletionId);
        assertThat(queued).doesNotContain(resultKey, stringAsOwner("SELECT blob_key FROM artifact WHERE id = ?", keeperDocxId));
        assertThat(blobStore.sizeOf(resultKey)).isPresent();
        // What only the doomed document used did go: its notes.
        assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE id = ?", notesId)).isZero();
    }

    /**
     * A source can be attached to the workspace and cited by a document's
     * evidence without ever being linked to that document. It is still that
     * document's source, and "delete forever" must not leave the person's
     * notes behind just because no link row names them.
     */
    @Test
    void aSourceTheDocumentOnlyCitesIsDeletedWithIt() throws Exception {
        Owner owner = signIn("subject-cited-source");
        long notesId = uploadAndFinalize(owner, "Treasurer: Priya Rao. The budget was approved.");
        long snapshotId = readJson(mockMvc.perform(post("/api/v1/workspaces/" + owner.workspaceId() + "/sources")
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"artifactId\":" + notesId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        long spanId = readJson(mockMvc.perform(post("/api/v1/workspaces/" + owner.workspaceId() + "/sources/" + snapshotId + "/spans")
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"type\":\"PLAIN_TEXT\",\"startCodePoint\":11,\"endCodePointExclusive\":20}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        long documentId = createDocument(owner, "Minutes that cite a source");
        long revisionId = currentRevisionId(owner, documentId);
        mockMvc.perform(patch(documentsPath(owner) + "/" + documentId + "/content")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("""
                                {
                                  "expectedRevisionId": %d,
                                  "edits": [{"operation": "SET", "fieldId": "meeting.title",
                                             "value": {"type": "TEXT", "cardinality": "SCALAR", "value": "Budget approval",
                                                       "evidenceSourceSpanIds": [%d]}}],
                                  "editReason": "Cited the treasurer's notes."
                                }
                                """.formatted(revisionId, spanId)))
                .andExpect(status().isOk());
        assertThat(countAsOwner("SELECT count(*) FROM document_source WHERE document_id = ?", documentId)).isZero();

        long deletionId = trash(owner, documentId);
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingObjectCount").value(1));

        assertThat(countAsOwner("SELECT count(*) FROM artifact WHERE id = ?", notesId)).isZero();
        assertThat(countAsOwner("SELECT count(*) FROM source_snapshot WHERE id = ?", snapshotId)).isZero();
        assertThat(countAsOwner("SELECT count(*) FROM source_span WHERE id = ?", spanId)).isZero();
        assertThat(countAsOwner("SELECT count(*) FROM plain_text_extraction_version WHERE artifact_id = ?", notesId)).isZero();
    }

    /** Entirely or not at all: while a worker still holds a job, the workspace, its documents and every session are exactly as they were. */
    @Test
    void aWorkspaceWithAJobStillRunningIsNotDeletedAndNobodyIsSignedOut() throws Exception {
        Owner owner = signIn("subject-workspace-busy");
        long documentId = createDocument(owner, "Minutes with a running job");
        long notesId = uploadAndFinalize(owner, "Notes that a worker is reading right now.");
        long jobId = readJson(mockMvc.perform(post(documentsPath(owner) + "/" + documentId + "/generations")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + notesId + "}"))
                .andExpect(status().isAccepted()).andReturn()).get("jobId").asLong();
        updateAsOwner("""
                UPDATE job SET state = 'LEASED', lease_owner = 'worker-under-test', fencing_token = 1, attempt_count = 1,
                               lease_expires_at = now() + interval '2 minutes'
                WHERE id = ?
                """, jobId);

        mockMvc.perform(post(deletionsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_WAITING_FOR_RUNNING_WORK"));

        mockMvc.perform(get("/api/v1/me").cookie(owner.session())).andExpect(status().isOk());
        mockMvc.perform(get(documentsPath(owner) + "/" + documentId).cookie(owner.session())).andExpect(status().isOk());
        assertThat(countAsOwner("SELECT count(*) FROM deletion_request WHERE workspace_id = ?", owner.workspaceId())).isZero();
        // The one thing that did change is what makes the next attempt succeed.
        assertThat(stringAsOwner("SELECT state FROM job WHERE id = ?", jobId)).isEqualTo("CANCEL_REQUESTED");

        updateAsOwner("UPDATE job SET lease_expires_at = now() - interval '1 second' WHERE id = ?", jobId);
        mockMvc.perform(post(deletionsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").cookie(owner.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherWorkspaceCanNeitherSeeNorTouchADeletion() throws Exception {
        Owner owner = signIn("subject-deletion-victim");
        Owner outsider = signIn("subject-deletion-outsider");
        long documentId = createDocument(owner, "Private minutes");
        long deletionId = trash(owner, documentId);

        // The owner's workspace path is closed to a non-member.
        mockMvc.perform(get(deletionsPath(owner)).cookie(outsider.session())).andExpect(status().isForbidden());
        mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(outsider.session()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(deletionsPath(owner))
                        .cookie(outsider.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isForbidden());

        // Under the outsider's own path, the owner's ids name nothing.
        mockMvc.perform(get(deletionsPath(outsider) + "/" + deletionId).cookie(outsider.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(deletionsPath(outsider) + "/" + deletionId + "/restore").cookie(outsider.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(deletionsPath(outsider) + "/" + deletionId + "/purge").cookie(outsider.session()).with(csrf()))
                .andExpect(status().isNotFound());
        long otherDocumentId = createDocument(owner, "Another private document");
        mockMvc.perform(post(deletionsPath(outsider))
                        .cookie(outsider.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + otherDocumentId + "}"))
                .andExpect(status().isNotFound());
        assertThat(readJson(mockMvc.perform(get(deletionsPath(outsider)).cookie(outsider.session()))
                .andExpect(status().isOk()).andReturn())).isEmpty();

        // Nothing the outsider tried changed anything for the owner.
        mockMvc.perform(get(deletionsPath(owner) + "/" + deletionId).cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TRASHED"));
        mockMvc.perform(get(documentsPath(owner) + "/" + otherDocumentId).cookie(owner.session())).andExpect(status().isOk());
    }

    @Test
    void theApplicationsOwnDatabaseLoginCanReadTheLedgerButNeverWriteItOrRunItsInternals() throws Exception {
        Owner owner = signIn("subject-ledger-permissions");
        long deletionId = trash(owner, createDocument(owner, "Ledger permissions"));

        try (Connection connection = apiDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(owner.userId()));
                context.executeQuery();
            }
            try (PreparedStatement read = connection.prepareStatement("SELECT state FROM deletion_request WHERE id = ?")) {
                read.setLong(1, deletionId);
                try (ResultSet rs = read.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("TRASHED");
                }
            }
            connection.rollback();

            for (String statement : List.of(
                    "UPDATE deletion_request SET state = 'RESTORED', restored_at = now() WHERE id = " + deletionId,
                    "DELETE FROM deletion_request WHERE id = " + deletionId,
                    "INSERT INTO deletion_blob_task (workspace_id, deletion_request_id, object_key) VALUES ("
                            + owner.workspaceId() + ", " + deletionId + ", 'workspace-1/anything')",
                    "SELECT retention_execute_purge(" + deletionId + ")",
                    "SELECT retention_purge_workspace(" + deletionId + ", " + owner.workspaceId() + ")",
                    "SELECT * FROM worker_purge_expired_trash(10)",
                    "SELECT * FROM worker_collect_pending_blob_deletions(10)",
                    "UPDATE document SET trashed_at = NULL WHERE id > 0",
                    "UPDATE audit_event SET action = 'DOCUMENT_RESTORED' WHERE workspace_id = " + owner.workspaceId(),
                    "DELETE FROM audit_event WHERE workspace_id = " + owner.workspaceId(),
                    "SELECT audit_append(" + owner.workspaceId() + ", " + owner.userId() + ", 'DOCUMENT_TRASHED', 'document', 1, '{}'::jsonb)",
                    "SELECT worker_expire_audit_events(86400000, 10)")) {
                try (PreparedStatement forbidden = connection.prepareStatement(statement)) {
                    assertThatThrownBy(forbidden::execute)
                            .as(statement)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                } finally {
                    connection.rollback();
                }
            }

            // An audit row may be written only in the acting person's own name: one in somebody else's,
            // or in the system's, is refused by the table's own policy.
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(owner.userId()));
                context.executeQuery();
            }
            for (String actor : List.of(String.valueOf(owner.userId() + 1_000_000), "NULL")) {
                try (PreparedStatement forged = connection.prepareStatement(
                        "INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id) VALUES ("
                                + owner.workspaceId() + ", " + actor + ", 'DOCUMENT_EXPORTED', 'document', 1)")) {
                    assertThatThrownBy(forged::execute)
                            .as("audit row written as " + actor)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("row-level security");
                } finally {
                    connection.rollback();
                }
            }

            // A plain DELETE is refused differently: this login holds the
            // table privilege, but no table has a DELETE policy, so row-level
            // security lets it match nothing. Deleting is only ever done by
            // the routines.
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(owner.userId()));
                context.executeQuery();
            }
            for (String table : List.of("document", "document_revision", "artifact", "job", "workspace_member")) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE workspace_id = " + owner.workspaceId())) {
                    assertThat(delete.executeUpdate()).as("rows deleted from %s", table).isZero();
                }
            }
            connection.rollback();
        }
        assertThat(countAsOwner("SELECT count(*) FROM document WHERE workspace_id = ?", owner.workspaceId())).isEqualTo(1);
    }

    @Test
    void deletingAWorkspaceRemovesEverythingEndsEverySessionAndTheNextSignInStartsEmpty() throws Exception {
        Owner owner = signIn("subject-workspace-deletion");
        Cookie secondDevice = newSession("subject-workspace-deletion");
        long documentId = createDocument(owner, "Minutes in a workspace that is going away");
        long sourceArtifactId = uploadAndFinalize(owner, "Notes that go with the workspace.");
        mockMvc.perform(post(documentsPath(owner) + "/" + documentId + "/generations")
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + sourceArtifactId + "}"))
                .andExpect(status().isAccepted());
        trash(owner, createDocument(owner, "Already in the trash"));
        List<String> everyKey = stringsAsOwner("SELECT blob_key FROM artifact WHERE workspace_id = ?", owner.workspaceId());
        assertThat(everyKey).hasSizeGreaterThan(2);

        // A request that names a document as well is refused before anything happens.
        mockMvc.perform(post(deletionsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"scope\":\"WORKSPACE\",\"documentId\":" + documentId + "}"))
                .andExpect(status().isBadRequest());

        JsonNode deleted = readJson(mockMvc.perform(post(deletionsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk()).andReturn());
        long deletionId = deleted.get("deletionId").asLong();

        // Both devices are signed out, not only the one that asked.
        mockMvc.perform(get("/api/v1/me").cookie(owner.session())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").cookie(secondDevice)).andExpect(status().isUnauthorized());

        assertThat(countAsOwner("SELECT count(*) FROM workspace WHERE id = ?", owner.workspaceId())).isZero();
        for (String table : WORKSPACE_TABLES) {
            assertThat(countAsOwner("SELECT count(*) FROM " + table + " WHERE workspace_id = ?", owner.workspaceId()))
                    .as("rows left in %s", table)
                    .isZero();
        }
        assertThat(countAsOwner("SELECT count(*) FROM user_identity WHERE id = ?", owner.userId())).isZero();
        assertThat(stringAsOwner("SELECT state FROM deletion_request WHERE id = ?", deletionId)).isEqualTo("PURGED");
        assertThat(stringsAsOwner("SELECT object_key FROM deletion_blob_task WHERE deletion_request_id = ?", deletionId))
                .containsAll(everyKey);
        // What outlives the workspace is the record that it was deleted, by whom, and how much went with it.
        assertThat(stringAsOwner("""
                        SELECT action || '|' || actor_user_id || '|' || ((details -> 'documentsRemoved') IS NOT NULL) || '|' || (details ->> 'identityRemoved')
                        FROM audit_event WHERE resource_type = 'workspace' AND resource_id = ?
                        """, owner.workspaceId()))
                .isEqualTo("WORKSPACE_DELETED|" + owner.userId() + "|true|true");
        // The earlier trash entry was closed by the workspace going away, not left open forever.
        assertThat(countAsOwner(
                        "SELECT count(*) FROM deletion_request WHERE workspace_id = ? AND state = 'TRASHED'", owner.workspaceId()))
                .isZero();

        // Signing in again is a new person with a new, empty workspace.
        Owner returned = signIn("subject-workspace-deletion");
        assertThat(returned.workspaceId()).isNotEqualTo(owner.workspaceId());
        assertThat(returned.userId()).isNotEqualTo(owner.userId());
        assertThat(readJson(mockMvc.perform(get(documentsPath(returned)).cookie(returned.session()))
                .andExpect(status().isOk()).andReturn())).isEmpty();
        assertThat(readJson(mockMvc.perform(get(deletionsPath(returned)).cookie(returned.session()))
                .andExpect(status().isOk()).andReturn())).isEmpty();
    }

    private record Owner(Cookie session, long workspaceId, long userId) {
    }

    private Owner signIn(String subject) throws Exception {
        Cookie session = newSession(subject);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
        return new Owner(session, workspaceId, userId);
    }

    private long createDocument(Owner owner, String title) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + owner.workspaceId() + "/templates")
                        .cookie(owner.session()))
                .andExpect(status().isOk()).andReturn());
        JsonNode flowing = null;
        for (JsonNode template : templates) {
            if (template.get("displayName").asText().equals("Flowing meeting minutes")) {
                flowing = template;
            }
        }
        assertThat(flowing).isNotNull();
        String body = """
                {
                  "title": "%s",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "%s"},
                    "meeting.date": {"type": "DATE", "cardinality": "SCALAR", "value": "2026-03-05"}
                  },
                  "initialRevisionReason": "Created for a deletion test."
                }
                """.formatted(title, flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong(), title);
        return readJson(mockMvc.perform(post(documentsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private long currentRevisionId(Owner owner, long documentId) throws Exception {
        return readJson(mockMvc.perform(get(documentsPath(owner) + "/" + documentId).cookie(owner.session()))
                .andExpect(status().isOk()).andReturn()).get("currentRevision").get("id").asLong();
    }

    private long trash(Owner owner, long documentId) throws Exception {
        return readJson(mockMvc.perform(post(deletionsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private static String editBody(long expectedRevisionId, String title) {
        return """
                {
                  "expectedRevisionId": %d,
                  "edits": [{"operation": "SET", "fieldId": "meeting.title", "value": {"type": "TEXT", "cardinality": "SCALAR", "value": "%s"}}],
                  "editReason": "Edited in a deletion test."
                }
                """.formatted(expectedRevisionId, title);
    }

    private long uploadAndFinalize(Owner owner, String text) throws Exception {
        String uploads = "/api/v1/workspaces/" + owner.workspaceId() + "/uploads";
        long artifactId = readJson(mockMvc.perform(post(uploads)
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"filename\":\"notes.txt\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put(uploads + "/" + artifactId + "/content")
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/octet-stream")
                        .content(text.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        mockMvc.perform(post(uploads + "/" + artifactId + "/complete").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        return artifactId;
    }

    private static String documentsPath(Owner owner) {
        return "/api/v1/workspaces/" + owner.workspaceId() + "/documents";
    }

    private static String deletionsPath(Owner owner) {
        return "/api/v1/workspaces/" + owner.workspaceId() + "/deletions";
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** As the table owner, which sees every row whatever the row-level security policies say: the only honest way to check that nothing is left. */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    private static long countAsOwner(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String stringAsOwner(String sql, long parameter) throws SQLException {
        List<String> values = stringsAsOwner(sql, parameter);
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private static List<String> stringsAsOwner(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
                return values;
            }
        }
    }

    private void attachSource(Owner owner, long documentId, long artifactId) throws Exception {
        mockMvc.perform(post(documentsPath(owner) + "/" + documentId + "/sources")
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"artifactId\":" + artifactId + "}"))
                .andExpect(status().isCreated());
    }

    /** What the worker's publish routine leaves behind: a ready artifact stored under its staged object's own key. */
    private long publishResultAsOwner(long workspaceId, long jobId, String objectKey) throws Exception {
        byte[] json = "{\"scalarFields\":{},\"repeatedItems\":[]}".getBytes(StandardCharsets.UTF_8);
        String sha256 = blobStore.writeAndDigest(objectKey, new java.io.ByteArrayInputStream(json), 1024).sha256Hex();
        try (Connection connection = ownerConnection()) {
            long artifactId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO artifact (workspace_id, blob_key, status, byte_count, sha256, detected_media_type, finalized_at)
                    VALUES (?, ?, 'READY', ?, ?, 'PLAIN_TEXT', now()) RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setString(2, objectKey);
                statement.setLong(3, json.length);
                statement.setString(4, sha256);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    artifactId = rs.getLong(1);
                }
            }
            long stagedOutputId;
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO job_staged_output (workspace_id, job_id, worker_id, fencing_token, output_kind, object_key,
                                                   sha256, byte_count, state, expires_at, attached_at)
                    VALUES (?, ?, 'worker-under-test', 1, 'extraction-result', ?, ?, ?, 'ATTACHED', now() + interval '1 hour', now())
                    RETURNING id
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
                statement.setString(3, objectKey);
                statement.setString(4, sha256);
                statement.setLong(5, json.length);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    stagedOutputId = rs.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO job_output_artifact (workspace_id, job_id, output_kind, staged_output_id, artifact_id)
                    VALUES (?, ?, 'extraction-result', ?, ?)
                    """)) {
                statement.setLong(1, workspaceId);
                statement.setLong(2, jobId);
                statement.setLong(3, stagedOutputId);
                statement.setLong(4, artifactId);
                statement.executeUpdate();
            }
            return artifactId;
        }
    }

    private static long insertOpenQuestionAsOwner(long workspaceId, long documentId) throws SQLException {
        String sql = """
                INSERT INTO question (workspace_id, document_id, field_id, reason, candidates)
                VALUES (?, ?, 'meeting.location', 'MISSING_REQUIRED', '[]'::jsonb) RETURNING id
                """;
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, workspaceId);
            statement.setLong(2, documentId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void updateAsOwner(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private Cookie newSession(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
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

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }
}
