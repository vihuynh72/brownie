package io.github.vihuynh72.brownie.api.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The controls that exist for when something has gone wrong for a real
 * person: starting work again after it gave up, letting support in and
 * taking that back, and the one query that says how the system is doing.
 * Each is checked from the database's side, as the table owner, because
 * what matters is what was really changed and recorded, and from the
 * application login's side, because what matters there is what it is
 * unable to do however it asks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        // Small enough to reach in a test, and more than any other test here asks for as one person (starting two
        // runs and starting each again is four); every other kind of request keeps its ordinary, generous allowance.
        "brownie.rate-limit.per-minute.model=6",
        // The same for anyone not signed in: six a minute refills one every ten seconds, so no pause on a slow machine
        // can hand a request back before the test has counted to seven. At the default of 120 it refills two a second.
        "brownie.rate-limit.per-minute.anonymous=6",
        // Deliberately not the defaults, so the test proves the page is told what is configured and not a constant.
        "brownie.retention.published.refused-file=PT48H",
        "brownie.retention.trash-days=14",
        "brownie.support.contact=data@example.org"})
@Testcontainers
class OperationalControlsIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-operations";

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
    private javax.sql.DataSource apiDataSource;

    @Test
    void aJobThatGaveUpIsStartedAgainOnceWithAFreshAttemptAndTheRestartIsOnTheRecord() throws Exception {
        Member member = signIn("subject-retry-dead-job");
        long documentId = createTitledDocument(member);
        long jobId = startGeneration(member, documentId);
        giveUpOn(jobId);
        long eventsBefore = count("SELECT count(*) FROM job_event WHERE job_id = ?", jobId);

        mockMvc.perform(post(retryPath(member, jobId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.attemptCount").value(0));

        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT state, attempt_count, manual_retry_count, lease_owner, cancellation_requested_at,
                               available_at <= clock_timestamp(), deadline_at > clock_timestamp() + interval '55 minutes'
                        FROM job WHERE id = ?
                        """)) {
            statement.setLong(1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("QUEUED");
                assertThat(rs.getInt(2)).as("a restart is a new run of attempts, not one more of the old").isZero();
                assertThat(rs.getInt(3)).isEqualTo(1);
                assertThat(rs.getString(4)).isNull();
                assertThat(rs.getObject(5)).isNull();
                assertThat(rs.getBoolean(6)).as("claimable now").isTrue();
                assertThat(rs.getBoolean(7)).as("with a deadline that is ahead of it again").isTrue();
            }
        }
        // A worker only hears about queued work through an event and its outbox row.
        assertThat(count("SELECT count(*) FROM job_event WHERE job_id = ?", jobId)).isEqualTo(eventsBefore + 1);
        assertThat(text("SELECT event_type || '/' || state FROM job_event WHERE job_id = ? ORDER BY sequence DESC LIMIT 1", jobId))
                .isEqualTo("QUEUED/QUEUED");
        assertThat(count("""
                SELECT count(*) FROM outbox_event o
                WHERE o.job_event_id = (SELECT id FROM job_event WHERE job_id = ? ORDER BY sequence DESC LIMIT 1)
                """, jobId)).isEqualTo(1);

        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT actor_user_id, workspace_id, details::text FROM audit_event WHERE action = 'JOB_RETRIED' AND resource_id = ?")) {
            statement.setLong(1, jobId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(member.userId());
                assertThat(rs.getLong(2)).isEqualTo(member.workspaceId());
                assertThat(rs.getString(3)).contains("\"previousState\": \"DEAD\"").contains("\"manualRetryCount\": 1");
                assertThat(rs.next()).isFalse();
            }
        }

        // Asked again while it is queued: the same job, as it is, and nothing started or recorded twice.
        mockMvc.perform(post(retryPath(member, jobId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED"));
        assertThat(count("SELECT manual_retry_count FROM job WHERE id = ?", jobId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'JOB_RETRIED' AND resource_id = ?", jobId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM job_event WHERE job_id = ?", jobId)).isEqualTo(eventsBefore + 1);
    }

    @Test
    void aJobWhoseDocumentHasMovedOnOrIsInTheTrashIsNotStartedAgain() throws Exception {
        Member member = signIn("subject-retry-stale-target");
        long editedDocumentId = createTitledDocument(member);
        long editedJobId = startGeneration(member, editedDocumentId);
        giveUpOn(editedJobId);
        long revisionId = readJson(mockMvc.perform(get(documentsPath(member) + "/" + editedDocumentId).cookie(member.session()))
                .andExpect(status().isOk()).andReturn()).get("currentRevision").get("id").asLong();
        mockMvc.perform(patch(documentsPath(member) + "/" + editedDocumentId + "/content")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("""
                                {
                                  "expectedRevisionId": %d,
                                  "edits": [{"operation": "SET", "fieldId": "meeting.title",
                                             "value": {"type": "TEXT", "cardinality": "SCALAR", "value": "Edited since the run began"}}],
                                  "editReason": "Edited in an operations test."
                                }
                                """.formatted(revisionId)))
                .andExpect(status().isOk());

        mockMvc.perform(post(retryPath(member, editedJobId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_TARGET_STALE"));

        long trashedDocumentId = createTitledDocument(member);
        long trashedJobId = startGeneration(member, trashedDocumentId);
        giveUpOn(trashedJobId);
        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + trashedDocumentId + "}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(retryPath(member, trashedJobId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_TARGET_STALE"));

        for (long jobId : List.of(editedJobId, trashedJobId)) {
            assertThat(text("SELECT state FROM job WHERE id = ?", jobId)).isEqualTo("DEAD");
            assertThat(count("SELECT manual_retry_count FROM job WHERE id = ?", jobId)).isZero();
            assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'JOB_RETRIED' AND resource_id = ?", jobId)).isZero();
        }
    }

    @Test
    void aJobThatFinishedIsNotStartedAgainAndNobodyRestartsAnotherWorkspacesJob() throws Exception {
        Member member = signIn("subject-retry-finished");
        Member stranger = signIn("subject-retry-stranger");
        long documentId = createTitledDocument(member);
        long jobId = startGeneration(member, documentId);

        update("UPDATE job SET state = 'CANCELLED', updated_at = now() WHERE id = ?", jobId);
        mockMvc.perform(post(retryPath(member, jobId)).cookie(member.session()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(text("SELECT state FROM job WHERE id = ?", jobId)).isEqualTo("CANCELLED");

        giveUpOn(jobId);
        // Through the stranger's own workspace the job does not exist; through the owner's, the stranger is not a member.
        mockMvc.perform(post(retryPath(stranger, jobId)).cookie(stranger.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(retryPath(member, jobId)).cookie(stranger.session()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(retryPath(member, 0)).cookie(member.session()).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(text("SELECT state FROM job WHERE id = ?", jobId)).isEqualTo("DEAD");

        // The routine itself refuses a caller who is not a member, whatever route was or was not in front of it.
        try (Connection connection = apiDataSource.getConnection()) {
            connection.setAutoCommit(false);
            actAs(connection, stranger.userId());
            try (PreparedStatement statement = connection.prepareStatement("SELECT retry_dead_job(?, ?)")) {
                statement.setLong(1, member.workspaceId());
                statement.setLong(2, jobId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("NOT_FOUND");
                }
            }
            connection.rollback();
        }
        assertThat(text("SELECT state FROM job WHERE id = ?", jobId)).isEqualTo("DEAD");
    }

    @Test
    void anOwnerLetsSupportInForABoundedTimeAndTakesItBack() throws Exception {
        Member owner = signIn("subject-support-grant-owner");
        assertThat(supportMayAct(owner.workspaceId(), "METADATA")).isFalse();

        JsonNode content = readJson(mockMvc.perform(post(grantsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"CONTENT\",\"days\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("CONTENT"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.revokedAt").doesNotExist())
                .andReturn());
        long contentGrantId = content.get("id").asLong();
        assertThat(Duration.between(
                        OffsetDateTime.parse(content.get("grantedAt").asText()), OffsetDateTime.parse(content.get("expiresAt").asText())))
                .as("three calendar days, which is an hour either side of 72 across a clock change")
                .isBetween(Duration.ofHours(71), Duration.ofHours(73));
        // Permission to read content is permission to read what describes it.
        assertThat(supportMayAct(owner.workspaceId(), "CONTENT")).isTrue();
        assertThat(supportMayAct(owner.workspaceId(), "METADATA")).isTrue();

        // One open grant per scope, so there is one answer to "until when".
        mockMvc.perform(post(grantsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"CONTENT\",\"days\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPORT_GRANT_ALREADY_OPEN"));
        mockMvc.perform(post(grantsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"METADATA\",\"days\":7}"))
                .andExpect(status().isCreated());

        for (String malformed : List.of(
                "{\"scope\":\"CONTENT\",\"days\":0}", "{\"scope\":\"CONTENT\",\"days\":8}", "{\"scope\":\"CONTENT\"}",
                "{\"scope\":\"EVERYTHING\",\"days\":1}", "{\"days\":1}")) {
            mockMvc.perform(post(grantsPath(owner))
                            .cookie(owner.session()).with(csrf())
                            .contentType("application/json").content(malformed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        mockMvc.perform(post(grantsPath(owner) + "/" + contentGrantId + "/revoke").cookie(owner.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedAt").exists());
        assertThat(supportMayAct(owner.workspaceId(), "CONTENT")).isFalse();
        assertThat(supportMayAct(owner.workspaceId(), "METADATA")).as("the other grant is still open").isTrue();
        // Taken back once; there is nothing open under that id any more.
        mockMvc.perform(post(grantsPath(owner) + "/" + contentGrantId + "/revoke").cookie(owner.session()).with(csrf()))
                .andExpect(status().isNotFound());
        // And having taken it back, the owner may give it again.
        mockMvc.perform(post(grantsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"CONTENT\",\"days\":1}"))
                .andExpect(status().isCreated());

        JsonNode listed = readJson(mockMvc.perform(get(grantsPath(owner)).cookie(owner.session()))
                .andExpect(status().isOk()).andReturn());
        assertThat(listed).hasSize(3);
        assertThat(listed.get(0).get("scope").asText()).as("most recent first").isEqualTo("CONTENT");
        assertThat(listed.get(0).get("active").asBoolean()).isTrue();

        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'SUPPORT_GRANT_CREATED' AND workspace_id = ?", owner.workspaceId()))
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'SUPPORT_GRANT_REVOKED' AND workspace_id = ?", owner.workspaceId()))
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM audit_event
                WHERE workspace_id = ? AND resource_type = 'support-grant' AND (actor_user_id IS NULL OR correlation_id IS NULL)
                """, owner.workspaceId())).as("every one in the owner's name, traceable to its request").isZero();

        // An expired grant stops counting without anyone doing anything.
        update("UPDATE support_grant SET granted_at = now() - interval '2 days', expires_at = now() - interval '1 day' WHERE workspace_id = ?",
                owner.workspaceId());
        assertThat(supportMayAct(owner.workspaceId(), "METADATA")).isFalse();
        JsonNode lapsed = readJson(mockMvc.perform(get(grantsPath(owner)).cookie(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].active").value(false))
                .andReturn());
        // What has already run out is not open, so there is nothing to take back and nothing to record as taken back.
        mockMvc.perform(post(grantsPath(owner) + "/" + lapsed.get(0).get("id").asLong() + "/revoke")
                        .cookie(owner.session()).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'SUPPORT_GRANT_REVOKED' AND workspace_id = ?", owner.workspaceId()))
                .isEqualTo(1);
    }

    @Test
    void aGrantAndTheRecordBelongToTheirWorkspaceAndTheApplicationsLoginCannotBendEither() throws Exception {
        Member owner = signIn("subject-support-grant-isolation");
        Member stranger = signIn("subject-support-grant-stranger");
        long grantId = readJson(mockMvc.perform(post(grantsPath(owner))
                        .cookie(owner.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"METADATA\",\"days\":2}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        mockMvc.perform(get(grantsPath(owner)).cookie(stranger.session())).andExpect(status().isForbidden());
        mockMvc.perform(post(grantsPath(owner))
                        .cookie(stranger.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"CONTENT\",\"days\":1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(grantsPath(stranger) + "/" + grantId + "/revoke").cookie(stranger.session()).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(grantsPath(stranger)).cookie(stranger.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(text("SELECT (revoked_at IS NULL)::text FROM support_grant WHERE id = ?", grantId)).isEqualTo("true");

        try (Connection connection = apiDataSource.getConnection()) {
            connection.setAutoCommit(false);

            // What the login has no permission for at all, whoever it is acting for.
            for (String statement : List.of(
                    "UPDATE support_grant SET expires_at = now() + interval '30 days' WHERE id = " + grantId,
                    "UPDATE support_grant SET scope = 'CONTENT' WHERE id = " + grantId,
                    "DELETE FROM support_grant WHERE id = " + grantId,
                    "SELECT support_grant_is_active(" + owner.workspaceId() + ", 'METADATA')",
                    "SELECT * FROM operations_summary()",
                    "UPDATE audit_event SET action = 'DOCUMENT_RESTORED' WHERE workspace_id = " + owner.workspaceId(),
                    "DELETE FROM audit_event WHERE workspace_id = " + owner.workspaceId(),
                    "SELECT audit_append(" + owner.workspaceId() + ", NULL, 'WORKSPACE_DELETED', 'workspace', 1, '{}'::jsonb)",
                    "SELECT worker_expire_audit_events(86400000, 10)")) {
                actAs(connection, owner.userId());
                try (PreparedStatement forbidden = connection.prepareStatement(statement)) {
                    assertThatThrownBy(forbidden::execute)
                            .as(statement)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                } finally {
                    connection.rollback();
                }
            }

            // What it has permission for, but not like this: longer than a week, or in somebody else's name.
            actAs(connection, owner.userId());
            try (PreparedStatement tooLong = connection.prepareStatement(
                    "INSERT INTO support_grant (workspace_id, granted_by_user_id, scope, expires_at) VALUES (?, ?, 'CONTENT', now() + interval '8 days')")) {
                tooLong.setLong(1, owner.workspaceId());
                tooLong.setLong(2, owner.userId());
                assertThatThrownBy(tooLong::execute).isInstanceOf(SQLException.class).hasMessageContaining("support_grant_bounded");
            } finally {
                connection.rollback();
            }
            // The week is counted from when the grant starts, so the owner's own login may not start one in the future.
            actAs(connection, owner.userId());
            try (PreparedStatement postdated = connection.prepareStatement(
                    "INSERT INTO support_grant (workspace_id, granted_by_user_id, scope, granted_at, expires_at)"
                            + " VALUES (?, ?, 'CONTENT', now() + interval '10 years', now() + interval '10 years 7 days')")) {
                postdated.setLong(1, owner.workspaceId());
                postdated.setLong(2, owner.userId());
                assertThatThrownBy(postdated::execute).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            } finally {
                connection.rollback();
            }
            for (String statement : List.of(
                    "INSERT INTO support_grant (workspace_id, granted_by_user_id, scope, expires_at) VALUES ("
                            + owner.workspaceId() + ", " + owner.userId() + ", 'CONTENT', now() + interval '1 day')",
                    "INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id) VALUES ("
                            + owner.workspaceId() + ", " + owner.userId() + ", 'DOCUMENT_EXPORTED', 'document', 1)",
                    "INSERT INTO audit_event (workspace_id, actor_user_id, action, resource_type, resource_id) VALUES ("
                            + stranger.workspaceId() + ", NULL, 'WORKSPACE_DELETED', 'workspace', 1)")) {
                actAs(connection, stranger.userId());
                try (PreparedStatement refused = connection.prepareStatement(statement)) {
                    assertThatThrownBy(refused::execute)
                            .as(statement)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("row-level security");
                } finally {
                    connection.rollback();
                }
            }

            // A stranger's revoke reaches no row, and the stranger sees neither the grant nor the record of it.
            actAs(connection, stranger.userId());
            try (PreparedStatement revoke = connection.prepareStatement(
                    "UPDATE support_grant SET revoked_at = now(), revoked_by_user_id = ? WHERE id = ?")) {
                revoke.setLong(1, stranger.userId());
                revoke.setLong(2, grantId);
                assertThat(revoke.executeUpdate()).isZero();
            }
            try (PreparedStatement read = connection.prepareStatement(
                            "SELECT (SELECT count(*) FROM support_grant) + (SELECT count(*) FROM audit_event WHERE workspace_id = ?)")) {
                read.setLong(1, owner.workspaceId());
                try (ResultSet rs = read.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }
            }
            connection.rollback();
        }
        assertThat(text("SELECT (revoked_at IS NULL)::text FROM support_grant WHERE id = ?", grantId)).isEqualTo("true");
    }

    @Test
    void theOperationsSummaryIsOneRowOfNumbersThatMoveWhenTheSystemDoes() throws Exception {
        Member member = signIn("subject-operations-summary");
        long[] before = operationsSummary();

        long documentId = createTitledDocument(member);
        long jobId = startGeneration(member, documentId);
        long[] queued = operationsSummary();
        assertThat(queued[column("jobs_queued")]).isGreaterThan(before[column("jobs_queued")]);

        giveUpOn(jobId);
        mockMvc.perform(post(grantsPath(member))
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"METADATA\",\"days\":1}"))
                .andExpect(status().isCreated());
        long otherDocumentId = createTitledDocument(member);
        mockMvc.perform(post("/api/v1/workspaces/" + member.workspaceId() + "/deletions")
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"scope\":\"DOCUMENT\",\"documentId\":" + otherDocumentId + "}"))
                .andExpect(status().isCreated());
        // Two reservations: one old enough to count as open too long, and one made now that carries the money. One row
        // could not do both, because in the first 45 minutes of a month the old one belongs to the month before.
        update("""
                INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card,
                                         reserved_input_tokens, reserved_output_tokens, reserved_cost_usd, created_at)
                VALUES (?, 1, 'ASSIST', 'seeded-model', 'seeded-prompt', 'seeded rates', 1, 1, 0.000000, now() - interval '45 minutes')
                """, member.workspaceId());
        update("""
                INSERT INTO model_usage (workspace_id, requested_by_user_id, purpose, model_name, prompt_version, rate_card,
                                         reserved_input_tokens, reserved_output_tokens, reserved_cost_usd, created_at)
                VALUES (?, 1, 'ASSIST', 'seeded-model', 'seeded-prompt', 'seeded rates', 1, 1, 0.250000, now())
                """, member.workspaceId());

        long[] after = operationsSummary();
        assertThat(after[column("jobs_dead_last_24h")]).isGreaterThan(before[column("jobs_dead_last_24h")]);
        assertThat(after[column("jobs_queued")]).isLessThan(queued[column("jobs_queued")]);
        assertThat(after[column("support_grants_active")]).isGreaterThan(before[column("support_grants_active")]);
        assertThat(after[column("trash_entries_open")]).isGreaterThan(before[column("trash_entries_open")]);
        assertThat(after[column("audit_events_last_24h")]).isGreaterThanOrEqualTo(before[column("audit_events_last_24h")] + 2);
        assertThat(after[column("model_requests_this_month")]).isGreaterThan(before[column("model_requests_this_month")]);
        assertThat(after[column("model_reservations_open_over_30_minutes")])
                .isGreaterThan(before[column("model_reservations_open_over_30_minutes")]);
        // Whole cents survive the truncation to a long: an open reservation counts at its full amount.
        assertThat((double) after[column("model_cost_this_month_usd_cents")])
                .isCloseTo(before[column("model_cost_this_month_usd_cents")] + 25.0, within(1.0));
    }

    /**
     * The "Your data" page shows numbers, and a number on a privacy page is a promise. They come from configuration,
     * the same variables the worker's sweeps run on, so this checks that what is configured is what is said.
     */
    @Test
    void whatPeopleAreToldAboutTheirDataIsWhatIsConfigured() throws Exception {
        Member member = signIn("subject-data-practices");

        mockMvc.perform(get("/api/v1/data-practices").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trashRetentionDays").value(14))
                .andExpect(jsonPath("$.abandonedUploadHours").value(24))
                .andExpect(jsonPath("$.refusedFileHours").value(48))
                .andExpect(jsonPath("$.unusedFileHours").value(24))
                .andExpect(jsonPath("$.auditRecordDays").value(90))
                .andExpect(jsonPath("$.modelProvider").value("OpenAI"))
                .andExpect(jsonPath("$.modelName").isNotEmpty())
                .andExpect(jsonPath("$.supportContact").value("data@example.org"));
        mockMvc.perform(get("/api/v1/data-practices")).andExpect(status().isUnauthorized());
    }

    /** Through the whole filter chain: a JSON body larger than anything this API reads is refused before any of it is parsed. */
    @Test
    void aJsonBodyFarLargerThanAnyRequestNeedsIsRefusedBeforeItIsRead() throws Exception {
        Member member = signIn("subject-oversized-json");
        String oversized = "{\"title\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";

        mockMvc.perform(post(documentsPath(member))
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(oversized))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("CONTENT_TOO_LARGE"));
        assertThat(count("SELECT count(*) FROM document WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    /**
     * A session whose person has no identity record. Nothing in the application produces one today (deleting a
     * workspace removes the record and ends the sessions together), which is exactly why it has to be made by hand
     * here: the answer must be "sign in again" and the end of that session, on any route, never a server error.
     */
    @Test
    void aSessionThatNamesNobodyIsToldToSignInAgainAndIsEnded() throws Exception {
        String subject = "subject-with-no-identity-record";
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, ISSUER)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));

        for (String route : List.of("/api/v1/me", "/api/v1/workspaces/1/documents", "/api/v1/workspaces/1/usage")) {
            Session session = createAuthenticatedSession(sessionRepository, context);
            Cookie cookie = new Cookie(
                    "SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));

            String body = mockMvc.perform(get(route).cookie(cookie))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SESSION_NO_LONGER_VALID"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).as(route).doesNotContain(subject).doesNotContain(ISSUER);
            assertThat(sessionRepository.findById(session.getId())).as("the session is ended, not left to fail again").isNull();
        }
    }

    /**
     * Over real HTTP, through the real filter chain: one person who asks for paid work faster than allowed is told to
     * wait, by a body in the API's own error shape and a Retry-After header, while another person and the same
     * person's ordinary reads go on as before.
     */
    @Test
    void onePersonAskingForPaidWorkTooFastIsToldToWaitAndNobodyElseIsAffected() throws Exception {
        Member hasty = signIn("subject-rate-limited");
        Member patient = signIn("subject-not-rate-limited");
        long documentId = createTitledDocument(hasty);
        String interpretOnly = "{\"text\":\"what is this\",\"expectedRevisionId\":1}";

        int refusedAt = -1;
        for (int i = 1; i <= 9 && refusedAt < 0; i++) {
            int status = mockMvc.perform(post(documentsPath(hasty) + "/" + documentId + "/assist/execute")
                            .cookie(hasty.session()).with(csrf())
                            .contentType("application/json").content(interpretOnly))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                refusedAt = i;
            }
        }
        assertThat(refusedAt).as("six a minute are allowed, so the seventh is the first refused").isEqualTo(7);

        mockMvc.perform(post(documentsPath(hasty) + "/" + documentId + "/assist/execute")
                        .cookie(hasty.session()).with(csrf())
                        .contentType("application/json").content(interpretOnly))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));

        mockMvc.perform(get(documentsPath(hasty) + "/" + documentId).cookie(hasty.session())).andExpect(status().isOk());
        long othersDocument = createTitledDocument(patient);
        assertThat(mockMvc.perform(post(documentsPath(patient) + "/" + othersDocument + "/assist/execute")
                        .cookie(patient.session()).with(csrf())
                        .contentType("application/json").content(interpretOnly))
                .andReturn().getResponse().getStatus()).isNotEqualTo(429);
    }

    /**
     * Most of what someone who is not signed in can send is answered by the sign-in machinery itself, before any
     * controller: a request with no session, the start of a sign-in. Each can leave a session row behind, so those
     * are the requests the allowance for an address has to count, and it is counted here through all of it.
     */
    @Test
    void someoneNotSignedInIsCountedByAddressEvenForWhatTheSignInMachineryAnswersItself() throws Exception {
        int refusedAt = -1;
        for (int i = 1; i <= 9 && refusedAt < 0; i++) {
            int status = mockMvc.perform(get("/api/v1/me").with(from("203.0.113.9"))).andReturn().getResponse().getStatus();
            if (status == 429) {
                refusedAt = i;
            } else {
                assertThat(status).isEqualTo(401);
            }
        }
        assertThat(refusedAt).as("six a minute are allowed, so the seventh is the first refused").isEqualTo(7);

        mockMvc.perform(get("/oauth2/authorization/entra").with(from("203.0.113.9")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        mockMvc.perform(get("/api/v1/me").with(from("203.0.113.10"))).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static final List<String> SUMMARY_COLUMNS = List.of(
            "jobs_queued", "jobs_leased", "jobs_waiting_for_input", "jobs_dead_last_24h", "oldest_queued_job_age_seconds",
            "jobs_leased_past_lease", "trash_entries_open", "trash_entries_overdue", "deletions_failed",
            "deletions_awaiting_verification", "stored_objects_awaiting_removal", "stored_object_removal_max_attempts",
            "uploads_in_progress", "scans_running_over_15_minutes", "files_quarantined", "refused_files_with_bytes_still_stored",
            "model_requests_this_month", "model_cost_this_month_usd", "model_reservations_open_over_30_minutes",
            "model_reservations_kept_unsettled_this_month", "support_grants_active", "audit_events_last_24h");

    /** The cost is carried as cents in the returned array, under its own name, so every figure fits one long. */
    private static int column(String name) {
        int index = SUMMARY_COLUMNS.indexOf(name.equals("model_cost_this_month_usd_cents") ? "model_cost_this_month_usd" : name);
        assertThat(index).as(name).isNotNegative();
        return index;
    }

    /** Read as the table owner, which is who it is for; asserts on the way that it is one row and that no column could carry text. */
    private static long[] operationsSummary() throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM operations_summary()");
                ResultSet rs = statement.executeQuery()) {
            ResultSetMetaData metaData = rs.getMetaData();
            assertThat(metaData.getColumnCount()).isEqualTo(SUMMARY_COLUMNS.size());
            assertThat(rs.next()).isTrue();
            long[] values = new long[SUMMARY_COLUMNS.size()];
            for (int i = 0; i < values.length; i++) {
                assertThat(metaData.getColumnName(i + 1)).isEqualTo(SUMMARY_COLUMNS.get(i));
                assertThat(metaData.getColumnType(i + 1))
                        .as(SUMMARY_COLUMNS.get(i) + " is a number")
                        .isIn(Types.BIGINT, Types.INTEGER, Types.NUMERIC);
                assertThat(rs.getObject(i + 1)).as(SUMMARY_COLUMNS.get(i) + " is never null").isNotNull();
                values[i] = metaData.getColumnType(i + 1) == Types.NUMERIC
                        ? rs.getBigDecimal(i + 1).movePointRight(2).longValue()
                        : rs.getLong(i + 1);
            }
            assertThat(rs.next()).isFalse();
            return values;
        }
    }

    private long startGeneration(Member member, long documentId) throws Exception {
        long notesId = uploadNotes(member);
        return readJson(mockMvc.perform(post(documentsPath(member) + "/" + documentId + "/generations")
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"sourceArtifactId\":" + notesId + "}"))
                .andExpect(status().isAccepted()).andReturn()).get("jobId").asLong();
    }

    /** What the worker leaves behind when every attempt has failed. No worker runs in this test, so the row is put in that state directly. */
    private static void giveUpOn(long jobId) throws SQLException {
        update("""
                UPDATE job SET state = 'DEAD', attempt_count = 3, lease_owner = NULL, lease_expires_at = NULL,
                               deadline_at = now() + interval '1 minute', updated_at = now()
                WHERE id = ?
                """, jobId);
    }

    private static boolean supportMayAct(long workspaceId, String scope) throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT support_grant_is_active(?, ?)")) {
            statement.setLong(1, workspaceId);
            statement.setString(2, scope);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void actAs(Connection connection, long userId) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            context.setString(1, String.valueOf(userId));
            context.executeQuery();
        }
    }

    private static void update(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            statement.executeUpdate();
        }
    }

    private static String retryPath(Member member, long jobId) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/jobs/" + jobId + "/retry";
    }

    private static String grantsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/support-grants";
    }

    private record Member(Cookie session, long workspaceId, long userId) {
    }

    private Member signIn(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
        builtInTemplateProvisioningService.ensureBuiltInTemplates(workspaceId, userId);
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
        Cookie cookie = new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
        return new Member(cookie, workspaceId, userId);
    }

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    /** A document whose title has text in it, so "shorten the meeting title" has something to send to the model. */
    private long createTitledDocument(Member member) throws Exception {
        JsonNode templates = readJson(mockMvc.perform(get("/api/v1/workspaces/" + member.workspaceId() + "/templates")
                        .cookie(member.session()))
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
                  "title": "Operations test minutes",
                  "templateId": %d,
                  "templateVersionId": %d,
                  "fields": {
                    "meeting.title": {"type": "TEXT", "cardinality": "SCALAR", "value": "A rather long meeting title that could be shorter"}
                  },
                  "initialRevisionReason": "Created for an operations test."
                }
                """.formatted(flowing.get("id").asLong(), flowing.get("currentActiveVersionId").asLong());
        return readJson(mockMvc.perform(post(documentsPath(member))
                        .cookie(member.session()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private long uploadNotes(Member member) throws Exception {
        String uploads = "/api/v1/workspaces/" + member.workspaceId() + "/uploads";
        long artifactId = readJson(mockMvc.perform(post(uploads)
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"filename\":\"notes.txt\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mockMvc.perform(put(uploads + "/" + artifactId + "/content")
                        .cookie(member.session()).with(csrf())
                        .contentType("application/octet-stream")
                        .content("The meeting was called to order.".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        mockMvc.perform(post(uploads + "/" + artifactId + "/complete").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        return artifactId;
    }

    private static String documentsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/documents";
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    private static long count(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String text(String sql, long parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
