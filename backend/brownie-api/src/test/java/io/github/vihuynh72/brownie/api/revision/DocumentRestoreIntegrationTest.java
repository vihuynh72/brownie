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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the restore route end to end against real infrastructure, the
 * same pattern {@code DocumentFieldStateIntegrationTest} uses: an earlier
 * revision comes back as a new one with every locked field kept as it is
 * now, a replay answers with the same revision, a stale or foreign request
 * changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class DocumentRestoreIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-document-restore";

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

    @Test
    void restoresAnEarlierVersionKeepingALockedFieldAndAReplayAnswersWithTheSameRevision() throws Exception {
        Cookie session = signInWithBuiltIns("subject-restore-happy");
        long workspaceId = workspaceOf("subject-restore-happy");
        long documentId = createMinimalDocument(session, workspaceId);
        long firstRevisionId = currentRevisionId(session, workspaceId, documentId);
        long editedRevisionId = editTitleAndDate(session, workspaceId, documentId, firstRevisionId);
        long lockedRevisionId = readJson(mockMvc.perform(post(documentPath(workspaceId, documentId) + "/fields/lock")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + editedRevisionId + ",\"fieldId\":\"meeting.date\",\"lock\":\"EXPLICITLY_LOCKED\"}"))
                .andExpect(status().isOk())
                .andReturn()).get("id").asLong();

        String key = UUID.randomUUID().toString();
        JsonNode restored = readJson(restore(session, workspaceId, documentId, firstRevisionId, key,
                        "{\"expectedRevisionId\":" + lockedRevisionId + "}")
                .andExpect(status().isOk())
                .andReturn());

        JsonNode revision = restored.get("revision");
        assertThat(restored.get("keptLockedFieldIds")).hasSize(1);
        assertThat(restored.get("keptLockedFieldIds").get(0).asText()).isEqualTo("meeting.date");
        assertThat(revision.get("revisionNumber").asInt()).isEqualTo(4);
        assertThat(revision.get("parentRevisionId").asLong()).isEqualTo(lockedRevisionId);
        assertThat(revision.get("editReason").asText()).isEqualTo("Restored version 1.");
        assertThat(revision.get("fields").get("meeting.title").get("value").asText()).isEqualTo("Weekly Sync");
        assertThat(revision.get("fields").get("meeting.date").get("value").asText()).isEqualTo("2026-04-01");
        assertThat(revision.get("fields").get("meeting.date").get("fieldState").get("lock").asText()).isEqualTo("EXPLICITLY_LOCKED");
        JsonNode document = readJson(mockMvc.perform(get(documentPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(document.get("currentRevisionId").asLong()).isEqualTo(revision.get("id").asLong());

        JsonNode replayed = readJson(restore(session, workspaceId, documentId, firstRevisionId, key,
                        "{\"expectedRevisionId\":" + lockedRevisionId + "}")
                .andExpect(status().isOk())
                .andReturn());
        assertThat(replayed.get("revision").get("id").asLong()).isEqualTo(revision.get("id").asLong());
        assertThat(replayed.get("keptLockedFieldIds")).isEqualTo(restored.get("keptLockedFieldIds"));

        restore(session, workspaceId, documentId, firstRevisionId, key,
                        "{\"expectedRevisionId\":" + lockedRevisionId + ",\"editReason\":\"A different request.\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        JsonNode history = readJson(mockMvc.perform(get(documentPath(workspaceId, documentId) + "/revisions").cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(history).hasSize(4);
    }

    @Test
    void aStaleExpectedRevisionIsRefusedWithTheCurrentOneAndAMalformedRequestChangesNothing() throws Exception {
        Cookie session = signInWithBuiltIns("subject-restore-stale");
        long workspaceId = workspaceOf("subject-restore-stale");
        long documentId = createMinimalDocument(session, workspaceId);
        long firstRevisionId = currentRevisionId(session, workspaceId, documentId);
        long editedRevisionId = editTitleAndDate(session, workspaceId, documentId, firstRevisionId);

        restore(session, workspaceId, documentId, firstRevisionId, UUID.randomUUID().toString(),
                        "{\"expectedRevisionId\":" + firstRevisionId + "}")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_REVISION"))
                .andExpect(jsonPath("$.currentRevisionId").value(editedRevisionId));
        restore(session, workspaceId, documentId, firstRevisionId, UUID.randomUUID().toString(),
                        "{\"expectedRevisionId\":" + editedRevisionId + ",\"editReason\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        restore(session, workspaceId, documentId, firstRevisionId, UUID.randomUUID().toString(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        JsonNode withReason = readJson(restore(session, workspaceId, documentId, firstRevisionId, UUID.randomUUID().toString(),
                        "{\"expectedRevisionId\":" + editedRevisionId + ",\"editReason\":\"" + "y".repeat(500) + "\"}")
                .andExpect(status().isOk())
                .andReturn());
        assertThat(withReason.get("revision").get("editReason").asText()).isEqualTo("y".repeat(500));
        assertThat(withReason.get("keptLockedFieldIds")).isEmpty();
        assertThat(withReason.get("revision").get("fields").get("meeting.title").get("value").asText()).isEqualTo("Weekly Sync");
    }

    @Test
    void aDocumentOrRevisionFromElsewhereIsNotFoundAndNothingIsAppended() throws Exception {
        Cookie ownerSession = signInWithBuiltIns("subject-restore-owner");
        long ownerWorkspaceId = workspaceOf("subject-restore-owner");
        long documentId = createMinimalDocument(ownerSession, ownerWorkspaceId);
        long firstRevisionId = currentRevisionId(ownerSession, ownerWorkspaceId, documentId);
        long editedRevisionId = editTitleAndDate(ownerSession, ownerWorkspaceId, documentId, firstRevisionId);
        long otherDocumentId = createMinimalDocument(ownerSession, ownerWorkspaceId);
        long otherDocumentsRevisionId = currentRevisionId(ownerSession, ownerWorkspaceId, otherDocumentId);
        Cookie strangerSession = signInWithBuiltIns("subject-restore-stranger");
        long strangerWorkspaceId = workspaceOf("subject-restore-stranger");

        restore(strangerSession, strangerWorkspaceId, documentId, firstRevisionId, UUID.randomUUID().toString(),
                        "{\"expectedRevisionId\":" + editedRevisionId + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        restore(ownerSession, ownerWorkspaceId, documentId, otherDocumentsRevisionId, UUID.randomUUID().toString(),
                        "{\"expectedRevisionId\":" + editedRevisionId + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        JsonNode history = readJson(mockMvc.perform(get(documentPath(ownerWorkspaceId, documentId) + "/revisions").cookie(ownerSession))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(history).hasSize(2);
    }

    private ResultActions restore(Cookie session, long workspaceId, long documentId, long revisionId, String key, String body)
            throws Exception {
        return mockMvc.perform(post(documentPath(workspaceId, documentId) + "/revisions/" + revisionId + "/restore")
                .cookie(session)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private long editTitleAndDate(Cookie session, long workspaceId, long documentId, long expectedRevisionId) throws Exception {
        return readJson(mockMvc.perform(patch(documentPath(workspaceId, documentId) + "/content")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"expectedRevisionId\":" + expectedRevisionId + ",\"editReason\":\"Changed the title and date.\","
                                + "\"edits\":["
                                + "{\"operation\":\"SET\",\"fieldId\":\"meeting.title\",\"value\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Renamed Sync\"}},"
                                + "{\"operation\":\"SET\",\"fieldId\":\"meeting.date\",\"value\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2026-04-01\"}}"
                                + "]}"))
                .andExpect(status().isOk())
                .andReturn()).get("id").asLong();
    }

    private long createMinimalDocument(Cookie session, long workspaceId) throws Exception {
        long[] template = findFlowingTemplateAndActiveVersion(session, workspaceId);
        String body = "{"
                + "\"title\":\"Weekly Sync\","
                + "\"templateId\":" + template[0] + ","
                + "\"templateVersionId\":" + template[1] + ","
                + "\"fields\":{\"meeting.title\":{\"type\":\"TEXT\",\"cardinality\":\"SCALAR\",\"value\":\"Weekly Sync\"},"
                + "\"meeting.date\":{\"type\":\"DATE\",\"cardinality\":\"SCALAR\",\"value\":\"2026-03-12\"}},"
                + "\"initialRevisionReason\":\"Created for a restore test.\"}";
        JsonNode created = readJson(mockMvc.perform(post("/api/v1/workspaces/" + workspaceId + "/documents")
                        .cookie(session)
                        .with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
        return created.get("id").asLong();
    }

    private long currentRevisionId(Cookie session, long workspaceId, long documentId) throws Exception {
        JsonNode document = readJson(mockMvc.perform(get(documentPath(workspaceId, documentId)).cookie(session))
                .andExpect(status().isOk())
                .andReturn());
        return document.get("currentRevision").get("id").asLong();
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

    private JsonNode readJson(org.springframework.test.web.servlet.MvcResult result) throws Exception {
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
        return new Cookie(
                "SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private Workspace ensureWorkspace(String subject) {
        var identity = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
        return workspaceRepository.ensurePersonalWorkspace(identity.id());
    }

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }
}
