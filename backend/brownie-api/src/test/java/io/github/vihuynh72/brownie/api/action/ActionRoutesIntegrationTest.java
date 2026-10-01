package io.github.vihuynh72.brownie.api.action;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.action.ActionRepository;
import io.github.vihuynh72.brownie.core.action.ActionRequest;
import io.github.vihuynh72.brownie.core.action.ActionType;
import io.github.vihuynh72.brownie.core.action.CanonicalJson;
import io.github.vihuynh72.brownie.core.action.NewAction;
import io.github.vihuynh72.brownie.core.document.DocumentPart;
import io.github.vihuynh72.brownie.core.document.DocumentPartKind;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.ExtractionVersion;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.CanonicalRequestHash;
import io.github.vihuynh72.brownie.core.job.IdempotencyKey;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The routes every kind of action shares, through the real security chain and
 * database, on a deployment where no change in anyone's account is offered:
 * reading an action shows exactly its payload and never a provider's
 * identifier; changing one needs the session's CSRF token; approving needs a
 * hash and, here, is refused with nothing recorded; another person's action
 * does not exist for anyone else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class ActionRoutesIntegrationTest {

    private static final String ISSUER = "https://issuer-action-routes";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActionRepository actionRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private ExtractionVersionRepository extractionVersionRepository;

    @Autowired
    private RevisionService revisionService;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void anActionReadsBackAsExactlyItsPayloadAndItsHashAndNeverAProvidersIdentifier() throws Exception {
        Member member = signIn("subject-action-routes-read");
        ActionRequest action = propose(member);

        JsonNode read = JSON.readTree(mockMvc.perform(get(actionPath(member, action.id())).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
        assertThat(read.get("state").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(read.get("type").asString()).isEqualTo("CALENDAR_CREATE_EVENT");
        assertThat(read.get("payloadHash").asString()).isEqualTo(action.payloadHash());
        assertThat(read.get("payload").get("title").asString()).isEqualTo("Planning meeting");
        assertThat(CanonicalJson.write(JSON.convertValue(read.get("payload"), Map.class)))
                .as("the payload as shown is the payload as approved").isEqualTo(action.payloadCanonical());
        assertThat(read.get("sent").asBoolean()).isFalse();
        assertThat(read.has("providerKey")).isFalse();
        assertThat(read.has("externalId")).isFalse();
        assertThat(read.toString()).doesNotContain(action.providerKey());

        mockMvc.perform(get(actionsPath(member)).param("documentId", String.valueOf(member.documentId())).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(action.id()));
        mockMvc.perform(get(actionsPath(member)).cookie(member.session()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        Member stranger = signIn("subject-action-routes-stranger");
        mockMvc.perform(get("/api/v1/workspaces/" + member.workspaceId() + "/actions/" + action.id()).cookie(stranger.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/workspaces/" + stranger.workspaceId() + "/actions/" + action.id()).cookie(stranger.session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void approvingNeedsTheSessionsTokenAndAHashAndWhereNoChangeIsOfferedRecordsNothingAndSendsNothing() throws Exception {
        Member member = signIn("subject-action-routes-approve");
        ActionRequest action = propose(member);
        String approve = actionPath(member, action.id()) + "/approve";
        String body = "{\"payloadHash\":\"" + action.payloadHash() + "\"}";

        mockMvc.perform(post(approve).cookie(member.session()).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(approve).cookie(member.session()).with(csrf()).contentType("application/json").content("{\"payloadHash\":\"approved\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(post(approve).cookie(member.session()).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTION_NOT_OFFERED"))
                .andExpect(jsonPath("$.actionType").value("CALENDAR_CREATE_EVENT"));

        ActionRequest after = actionRepository.find(member.workspaceId(), member.userId(), action.id()).orElseThrow();
        assertThat(after.approvedAt()).isNull();
        assertThat(actionRepository.attempts(member.workspaceId(), member.userId(), action.id())).isEmpty();
        assertThat(count("SELECT count(*) FROM audit_event WHERE resource_type = 'action' AND resource_id = ?", action.id())).isZero();

        mockMvc.perform(get("/api/v1/capabilities").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleActions.length()").value(0));
    }

    @Test
    void aWithdrawnActionIsAnsweredAsItIsWhenApprovedAgain() throws Exception {
        Member member = signIn("subject-action-routes-cancel");
        ActionRequest action = propose(member);

        mockMvc.perform(post(actionPath(member, action.id()) + "/acknowledge").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.outcomeAcknowledged").value(false));
        mockMvc.perform(post(actionPath(member, action.id()) + "/cancel").cookie(member.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(actionPath(member, action.id()) + "/cancel").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.finishedAt").exists());
        mockMvc.perform(post(actionPath(member, action.id()) + "/approve").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"payloadHash\":\"" + action.payloadHash() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        mockMvc.perform(post(actionPath(member, action.id()) + "/reconcile").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void anAttemptThatStoppedWithoutFinishingIsSaidSoAndCanBeAcknowledged() throws Exception {
        Member member = signIn("subject-action-routes-stopped");
        ActionRequest action = propose(member);
        assertThat(actionRepository.claim(member.workspaceId(), member.userId(), action.id(), action.payloadHash(), 120).outcome())
                .isEqualTo(io.github.vihuynh72.brownie.core.action.ActionRepository.ClaimOutcome.CLAIMED);

        mockMvc.perform(get(actionPath(member, action.id())).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EXECUTING"))
                .andExpect(jsonPath("$.attemptStopped").value(false));
        ownerUpdate("UPDATE action_request SET lease_expires_at = now() - interval '1 second' WHERE id = ?", action.id());
        mockMvc.perform(get(actionPath(member, action.id())).cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EXECUTING"))
                .andExpect(jsonPath("$.attemptStopped").value(true));
        mockMvc.perform(post(actionPath(member, action.id()) + "/acknowledge").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.outcomeAcknowledged").value(true))
                .andExpect(jsonPath("$.attemptStopped").value(false))
                .andExpect(jsonPath("$.sent").value(false));
    }

    // --- fixtures ---

    private record Member(Cookie session, long workspaceId, long userId, long documentId, long connectionId) {
    }

    private ActionRequest propose(Member member) {
        String payload = CanonicalJson.write(Map.of("title", "Planning meeting", "nonce", UUID.randomUUID().toString()));
        return actionRepository.propose(member.workspaceId(), member.userId(), new NewAction(
                member.documentId(), member.connectionId(), ActionType.CALENDAR_CREATE_EVENT, payload, CanonicalJson.sha256Hex(payload),
                null, null, null, null, "eventkey" + UUID.randomUUID().toString().replace("-", "")));
    }

    private Member signIn(String subject) throws SQLException {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        long userId = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
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
        long documentId = newDocument(workspaceId, userId);
        long connectionId = insertCalendarConnection(workspaceId, userId, "calendar-account-" + subject);
        return new Member(cookie, workspaceId, userId, documentId, connectionId);
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private long newDocument(long workspaceId, long userId) throws SQLException {
        long artifactId;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                context.setString(1, String.valueOf(userId));
                context.executeQuery();
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO artifact (workspace_id, blob_key, status, byte_count,"
                    + " detected_media_type) VALUES (?, ?, 'READY', 100, 'DOCX') RETURNING id")) {
                insert.setLong(1, workspaceId);
                insert.setString(2, "test-blob-" + UUID.randomUUID());
                try (ResultSet rs = insert.executeQuery()) {
                    rs.next();
                    artifactId = rs.getLong(1);
                }
            }
            connection.commit();
        }
        StructuralNode control = new StructuralNode("p0/sdt0", StructuralNodeKind.CONTENT_CONTROL, null, null, "meeting.title", null, List.of());
        StructuralNode root = new StructuralNode("body", StructuralNodeKind.BODY, null, null, null, null, List.of(control));
        ExtractionVersion extraction = extractionVersionRepository.saveComplete(workspaceId, userId, artifactId, "test-parser-v1",
                new DocxStructuralGraph("test-parser-v1", List.of(new DocumentPart("word/document.xml", DocumentPartKind.MAIN_DOCUMENT, root))));
        Template template = templateRepository.createDraft(workspaceId, userId, "Club Minutes", artifactId, extraction.id());
        TemplateVersion draft = templateRepository.replaceDraftBindings(workspaceId, userId, template.id(), 1, List.of(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title"))));
        TemplateVersion version = templateRepository.activate(workspaceId, userId, template.id(), draft.versionNumber());
        return revisionService.createDocument(workspaceId, userId, new IdempotencyKey("create-" + UUID.randomUUID()),
                CanonicalRequestHash.sha256OfCanonicalText("create-" + UUID.randomUUID()), "Test document", version.templateId(), version.id(),
                new DocumentContent(Map.of("meeting.title", new FieldValue.TextValue("Test meeting"))), Map.of(), "initial draft")
                .document().id();
    }

    private static long insertCalendarConnection(long workspaceId, long userId, String accountId) throws SQLException {
        try (Connection owner = DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement insert = owner.prepareStatement(
                        "INSERT INTO connector_connection (workspace_id, user_id, provider, access, account_id, account_email, granted_scopes,"
                                + " state, token_key_id, token_nonce, token_ciphertext, token_issued_at)"
                                + " VALUES (?, ?, 'GOOGLE', 'CALENDAR_EVENT_CREATION', ?, 'person@example.org', 'scope', 'ACTIVE', 'k', ?, ?, now())"
                                + " RETURNING id")) {
            insert.setLong(1, workspaceId);
            insert.setLong(2, userId);
            insert.setString(3, accountId);
            insert.setBytes(4, new byte[12]);
            insert.setBytes(5, new byte[32]);
            try (ResultSet rs = insert.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void ownerUpdate(String sql, long parameter) throws SQLException {
        try (Connection owner = DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static long count(String sql, long parameter) throws SQLException {
        try (Connection owner = DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
                PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String actionsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/actions";
    }

    private static String actionPath(Member member, long actionId) {
        return actionsPath(member) + "/" + actionId;
    }
}
