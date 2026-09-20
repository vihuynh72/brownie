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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * The deletion routines under two real requests at once. Each is argued
 * safe from the order in which it takes its locks; this drives them against
 * each other over HTTP, repeatedly, and checks the thing the argument
 * promises: whatever order the two land in, exactly one outcome happens,
 * and the database is left in a state that outcome explains.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.autoconfigure.exclude=", "brownie.rate-limit.enabled=false"})
@Testcontainers
class DeletionConcurrencyIntegrationTest {

    private static final int ROUNDS = 8;

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-deletion-races";

    /** Every table that carries a document id, which is exactly what must be empty for a document that was deleted for good. */
    /** Every table that carries a workspace id and must hold nothing of a deleted workspace. The two ledger tables are deliberately absent. */
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
    void restoreAndDeleteForeverRacedAgainstEachOtherNeverBothHappen() throws Exception {
        Owner owner = signIn("subject-race-restore-purge");
        for (int round = 0; round < ROUNDS; round++) {
            long documentId = createDocument(owner, "Raced minutes " + round);
            long deletionId = trash(owner, documentId);

            List<Integer> statuses = race(
                    () -> mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/restore").cookie(owner.session()).with(csrf()))
                            .andReturn().getResponse().getStatus(),
                    () -> mockMvc.perform(post(deletionsPath(owner) + "/" + deletionId + "/purge").cookie(owner.session()).with(csrf()))
                            .andReturn().getResponse().getStatus());

            String state = stringAsOwner("SELECT state FROM deletion_request WHERE id = ?", deletionId);
            long documents = countAsOwner("SELECT count(*) FROM document WHERE id = ?", documentId);
            assertThat(statuses).as("round %d: one request wins (200) and the other is told it is no longer open (409)", round)
                    .containsExactlyInAnyOrder(200, 409);
            if (statuses.get(0) == 200) {
                assertThat(state).isEqualTo("RESTORED");
                assertThat(documents).isEqualTo(1);
                assertThat(countAsOwner("SELECT count(*) FROM document WHERE id = ? AND trashed_at IS NULL", documentId)).isEqualTo(1);
            } else {
                assertThat(state).isIn("PURGED", "VERIFIED");
                assertThat(documents).isZero();
            }
        }
    }

    @Test
    void twoRequestsToTrashTheSameDocumentOpenOneEntryNotTwo() throws Exception {
        Owner owner = signIn("subject-race-double-trash");
        for (int round = 0; round < ROUNDS; round++) {
            long documentId = createDocument(owner, "Trashed twice " + round);
            String body = "{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}";

            List<Integer> statuses = race(
                    () -> mockMvc.perform(post(deletionsPath(owner)).cookie(owner.session()).with(csrf())
                            .contentType("application/json").content(body)).andReturn().getResponse().getStatus(),
                    () -> mockMvc.perform(post(deletionsPath(owner)).cookie(owner.session()).with(csrf())
                            .contentType("application/json").content(body)).andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).allMatch(status -> status == 201);
            assertThat(countAsOwner("SELECT count(*) FROM deletion_request WHERE scope = 'DOCUMENT' AND target_id = ?", documentId))
                    .as("round %d: one open entry", round).isEqualTo(1);
            assertThat(countAsOwner("SELECT count(*) FROM audit_event WHERE action = 'DOCUMENT_TRASHED' AND resource_id = ?", documentId))
                    .isEqualTo(1);
        }
    }

    @Test
    void anEditRacedAgainstTheTrashEitherLandsBeforeItOrIsRefusedNeverAfter() throws Exception {
        Owner owner = signIn("subject-race-edit-trash");
        for (int round = 0; round < ROUNDS; round++) {
            long documentId = createDocument(owner, "Edited while trashed " + round);
            long revisionId = currentRevisionId(owner, documentId);
            String trashBody = "{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}";

            List<Integer> statuses = race(
                    () -> mockMvc.perform(patch(documentsPath(owner) + "/" + documentId + "/content")
                                    .cookie(owner.session()).with(csrf())
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType("application/json").content(editBody(revisionId, "Edited in a race")))
                            .andReturn().getResponse().getStatus(),
                    () -> mockMvc.perform(post(deletionsPath(owner)).cookie(owner.session()).with(csrf())
                            .contentType("application/json").content(trashBody)).andReturn().getResponse().getStatus());

            assertThat(statuses.get(1)).as("round %d: the trash always succeeds", round).isEqualTo(201);
            long revisions = countAsOwner("SELECT count(*) FROM document_revision WHERE document_id = ?", documentId);
            if (statuses.get(0) == 200) {
                assertThat(revisions).as("round %d: the edit landed first", round).isEqualTo(2);
            } else {
                assertThat(statuses.get(0)).as("round %d: the edit was refused as if the document were gone", round).isEqualTo(404);
                assertThat(revisions).isEqualTo(1);
            }
            assertThat(countAsOwner("SELECT count(*) FROM document WHERE id = ? AND trashed_at IS NOT NULL", documentId)).isEqualTo(1);
        }
    }

    @Test
    void aRunStartedWhileItsDocumentIsBeingTrashedNeverLeavesWorkQueuedForADocumentInTheTrash() throws Exception {
        Owner owner = signIn("subject-race-start-trash");
        long notesId = uploadAndFinalize(owner, "The meeting was called to order at nine.");
        for (int round = 0; round < ROUNDS; round++) {
            long documentId = createDocument(owner, "Started while trashed " + round);
            attachSource(owner, documentId, notesId);
            String trashBody = "{\"scope\":\"DOCUMENT\",\"documentId\":" + documentId + "}";

            List<Integer> statuses = race(
                    () -> mockMvc.perform(post(documentsPath(owner) + "/" + documentId + "/generations")
                                    .cookie(owner.session()).with(csrf())
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType("application/json").content("{\"sourceArtifactId\":" + notesId + "}"))
                            .andReturn().getResponse().getStatus(),
                    () -> mockMvc.perform(post(deletionsPath(owner)).cookie(owner.session()).with(csrf())
                            .contentType("application/json").content(trashBody)).andReturn().getResponse().getStatus());

            assertThat(statuses.get(1)).as("round %d: the trash always succeeds", round).isEqualTo(201);
            assertThat(statuses.get(0)).as("round %d: the start is accepted or the document is already gone", round).isIn(202, 404);
            assertThat(countAsOwner("""
                    SELECT count(*) FROM job
                    WHERE resource_type = 'document' AND resource_id = ? AND state IN ('QUEUED', 'WAITING_FOR_INPUT', 'LEASED')
                    """, documentId)).as("round %d: nothing is left for a worker to pick up", round).isZero();
        }
    }

    /** Both requests are released at the same instant from a gate, so that they genuinely overlap instead of politely taking turns. */
    private static List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> {
                gate.await();
                return first.call();
            });
            Future<Integer> b = pool.submit(() -> {
                gate.await();
                return second.call();
            });
            gate.countDown();
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
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
