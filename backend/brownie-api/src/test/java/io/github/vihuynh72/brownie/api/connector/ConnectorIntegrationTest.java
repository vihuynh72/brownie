package io.github.vihuynh72.brownie.api.connector;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.vihuynh72.brownie.api.connector.google.GoogleConsentRequests;
import io.github.vihuynh72.brownie.core.connector.ConnectionReconnectRequiredException;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorService;
import io.github.vihuynh72.brownie.core.connector.GrantRevocationReason;
import io.github.vihuynh72.brownie.core.connector.ReconnectReason;
import io.github.vihuynh72.brownie.core.connector.ResourceGrant;
import io.github.vihuynh72.brownie.core.connector.ResourceGrantRepository;
import io.github.vihuynh72.brownie.core.connector.ResourceGrantType;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Connecting a Google account end to end: Brownie's real routes, sessions,
 * database and policies, with Google stood in for by WireMock over real
 * HTTP. What is checked is what a person and an attacker would meet: what
 * Google is sent, what is stored, what is refused, and what is left behind.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.public-origin=http://localhost:8081",
        "brownie.web.origin=http://localhost:5173",
        "brownie.connectors.google.client-id=stand-in-client.apps.googleusercontent.com",
        "brownie.connectors.google.client-secret=stand-in-client-secret-value",
        "brownie.connectors.token-key-id=test-key"})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ConnectorIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String WORKER_PASSWORD = "brownie_worker_local_only";
    private static final String ISSUER = "https://issuer-connectors";
    private static final String CALLBACK = "/api/v1/connectors/google/callback";
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events.owned.readonly";
    private static final String CLIENT_SECRET = "stand-in-client-secret-value";

    private static final WireMockServer GOOGLE = startedGoogle();
    private static final String TOKEN_KEY = randomKey();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword("postgres_bootstrap_only")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.connectors.token-key", () -> TOKEN_KEY);
        registry.add("brownie.connectors.google.token-uri", () -> GOOGLE.baseUrl() + "/token");
        registry.add("brownie.connectors.google.revocation-uri", () -> GOOGLE.baseUrl() + "/revoke");
        registry.add("brownie.connectors.google.user-info-uri", () -> GOOGLE.baseUrl() + "/userinfo");
        registry.add("brownie.connectors.google.api-base-uri", GOOGLE::baseUrl);
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
    private ConnectorService connectorService;

    @Autowired
    private ResourceGrantRepository resourceGrantRepository;

    @BeforeEach
    void resetGoogle() {
        GOOGLE.resetAll();
    }

    @AfterAll
    static void stopGoogle() {
        GOOGLE.stop();
    }

    @Test
    void startingAConsentAsksGoogleForOneKindOfAccessAndKeepsItsSecretsInTheSession() throws Exception {
        Member member = signIn("subject-start");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/capabilities").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleConnectorAccess.length()").value(1))
                .andExpect(jsonPath("$.googleConnectorAccess[0]").value("CALENDAR_EVENTS"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(connectionsPath(member) + "/google")
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"access\":\"DRIVE_FILES\",\"returnTo\":\"/documents/12\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        Map<String, String> query = queryOf(JSON.readTree(result.getResponse().getContentAsString()).get("authorizationUrl").asString());
        assertThat(query.get("scope")).isEqualTo(DRIVE_SCOPE);
        assertThat(query.get("redirect_uri")).isEqualTo("http://localhost:8081" + CALLBACK);
        assertThat(query.get("access_type")).isEqualTo("offline");
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query).doesNotContainKey("client_secret");

        PendingConsent pending = pendingIn(member);
        assertThat(pending.state()).isEqualTo(query.get("state"));
        assertThat(GoogleConsentRequests.challengeFor(pending.codeVerifier())).isEqualTo(query.get("code_challenge"));
        assertThat(pending.returnTo()).isEqualTo("/documents/12");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(pending.codeVerifier());
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId()))
                .as("nothing is stored before Google answers").isZero();
    }

    @Test
    void startingAConsentNeedsASessionItsCsrfTokenAKnownAccessAndAnOrdinaryReturnPage() throws Exception {
        Member member = signIn("subject-start-refused");
        String path = connectionsPath(member) + "/google";

        mockMvc.perform(MockMvcRequestBuilders.post(path).contentType("application/json").content("{\"access\":\"DRIVE_FILES\"}").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.post(path).cookie(member.session()).contentType("application/json").content("{\"access\":\"DRIVE_FILES\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.post(path).cookie(member.session()).with(csrf()).contentType("application/json").content("{\"access\":\"GMAIL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        for (String returnTo : List.of("https://elsewhere.example.org", "//elsewhere.example.org", "/documents/1/../../x", "/connections?x=1")) {
            mockMvc.perform(MockMvcRequestBuilders.post(path).cookie(member.session()).with(csrf()).contentType("application/json")
                            .content("{\"access\":\"DRIVE_FILES\",\"returnTo\":\"" + returnTo + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        // Another person's workspace is not this person's to connect anything to.
        Member stranger = signIn("subject-start-stranger");
        mockMvc.perform(MockMvcRequestBuilders.post(connectionsPath(stranger) + "/google").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"access\":\"DRIVE_FILES\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCompletedConsentKeepsOnlyCiphertextAndSendsThePersonBackWhereTheyStarted(CapturedOutput output) throws Exception {
        Member member = signIn("subject-connect");
        stubConsent(DRIVE_SCOPE, "1//refresh-drive-connect", "ya29.access-drive-connect");
        stubDriveAccount("perm-0001", "person@example.org");
        Map<String, String> query = startConsent(member, "DRIVE_FILES", "/connections");

        MvcResult callback = mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK)
                        .param("code", "4/stand-in-code-value").param("state", query.get("state")).cookie(member.session()))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo("http://localhost:5173/connections?google=connected&access=drive_files");
        LoggedRequest exchange = GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token"))).getFirst();
        Map<String, String> form = formOf(exchange.getBodyAsString());
        assertThat(form.get("code")).isEqualTo("4/stand-in-code-value");
        assertThat(GoogleConsentRequests.challengeFor(form.get("code_verifier")))
                .as("the verifier sent with the code is the one whose digest went to the consent page")
                .isEqualTo(query.get("code_challenge"));

        try (Connection connection = ownerConnection(); PreparedStatement read = connection.prepareStatement(
                "SELECT state, account_id, account_email, granted_scopes, token_key_id, token_ciphertext, provider"
                        + " FROM connector_connection WHERE workspace_id = ?")) {
            read.setLong(1, member.workspaceId());
            try (ResultSet rs = read.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                assertThat(rs.getString("account_id")).isEqualTo("perm-0001");
                assertThat(rs.getString("account_email")).isEqualTo("person@example.org");
                assertThat(rs.getString("granted_scopes")).isEqualTo(DRIVE_SCOPE);
                assertThat(rs.getString("token_key_id")).isEqualTo("test-key");
                assertThat(new String(rs.getBytes("token_ciphertext"), StandardCharsets.ISO_8859_1)).doesNotContain("refresh-drive-connect");
                assertThat(rs.getString("provider")).isEqualTo("GOOGLE");
                assertThat(rs.next()).isFalse();
            }
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE workspace_id = ? AND action = 'CONNECTOR_CONNECTED'", member.workspaceId()))
                .isEqualTo(1);

        MvcResult list = mockMvc.perform(MockMvcRequestBuilders.get(connectionsPath(member))
                        .cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].access").value("DRIVE_FILES"))
                .andExpect(jsonPath("$[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$[0].accountEmail").value("person@example.org"))
                .andExpect(jsonPath("$[0].grantedScopes[0]").value(DRIVE_SCOPE))
                .andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain("refresh").doesNotContain("perm-0001").doesNotContain("token_");

        // The same answer again finds nothing pending: a consent is completed once.
        mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK)
                        .param("code", "4/stand-in-code-value").param("state", query.get("state")).cookie(member.session()))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).endsWith("reason=no_pending_request"));
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token")))).hasSize(1);

        assertThat(output.getAll())
                .doesNotContain("refresh-drive-connect")
                .doesNotContain("access-drive-connect")
                .doesNotContain("stand-in-code-value")
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(form.get("code_verifier"));
    }

    @Test
    void anAnswerThatDoesNotMatchWhatThisSessionStartedIsRefusedAndNothingIsStored() throws Exception {
        Member member = signIn("subject-mismatch");
        stubConsent(DRIVE_SCOPE, "1//refresh-mismatch", "ya29.access-mismatch");
        stubDriveAccount("perm-0002", null);

        Map<String, String> query = startConsent(member, "DRIVE_FILES", "/connections");
        assertThat(callback(member, "code", "not-the-state")).endsWith("google=failed&access=drive_files&reason=state_mismatch");
        assertThat(callback(member, "code", query.get("state")))
                .as("a wrong answer used the pending consent up, so the right one arriving later finds nothing")
                .endsWith("reason=no_pending_request");

        startConsent(member, "DRIVE_FILES", "/connections");
        assertThat(callbackWithError(member, "access_denied")).endsWith("google=failed&access=drive_files&reason=access_denied");
        startConsent(member, "DRIVE_FILES", "/connections");
        assertThat(callbackWithError(member, "a thing & an <other>")).endsWith("reason=provider_error");

        // Started in one person's session, answered in another's: the second session has nothing pending.
        Map<String, String> started = startConsent(member, "DRIVE_FILES", "/connections");
        Member other = signIn("subject-mismatch-other");
        assertThat(callback(other, "code", started.get("state"))).endsWith("reason=no_pending_request");

        // No session at all: sent to a page that asks them to sign in, not left on a bare error.
        mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK)
                        .param("code", "code").param("state", started.get("state")))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isEqualTo("http://localhost:5173/connections?google=failed&reason=signed_out"));

        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/token")))).isEmpty();
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();
    }

    @Test
    void aConsentMissingItsPermissionOrFromAnotherAccountIsRefusedWithItsReason() throws Exception {
        Member member = signIn("subject-refused-consent");

        stubConsent("openid", "1//refresh-unticked", "ya29.access-unticked");
        startConsentAndAnswer(member, "DRIVE_FILES", "permission_not_granted");
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();

        stubConsent(DRIVE_SCOPE, "1//refresh-first-account", "ya29.access-first-account");
        stubDriveAccount("perm-first", "first@example.org");
        startConsentAndAnswer(member, "DRIVE_FILES", null);

        stubConsent(DRIVE_SCOPE, "1//refresh-second-account", "ya29.access-second-account");
        stubDriveAccount("perm-second", "second@example.org");
        startConsentAndAnswer(member, "DRIVE_FILES", "different_account");
        assertThat(text("SELECT account_id FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isEqualTo("perm-first");
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/revoke")))).as("a refused consent is not revoked").isEmpty();
    }

    @Test
    void aDriveRefusalAfterConsentNamesWhoCanFixItRatherThanAnExpiredConsentAndKeepsNothing(CapturedOutput output) throws Exception {
        Member member = signIn("subject-drive-api-off");
        stubConsent(DRIVE_SCOPE, "1//refresh-api-off", "ya29.access-api-off");
        stubDriveRefusal("{\"error\":{\"code\":403,\"errors\":[{\"domain\":\"usageLimits\",\"reason\":\"accessNotConfigured\"}],"
                + "\"status\":\"PERMISSION_DENIED\"}}");

        startConsentAndAnswer(member, "DRIVE_FILES", "not_configured");

        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(output.getAll())
                .contains("Google consent for DRIVE_FILES did not complete (not_configured)")
                .contains("accessNotConfigured, PERMISSION_DENIED")
                .doesNotContain("1//refresh-api-off")
                .doesNotContain("ya29.access-api-off");

        Member managed = signIn("subject-drive-domain-policy");
        stubConsent(DRIVE_SCOPE, "1//refresh-domain-policy", "ya29.access-domain-policy");
        stubDriveRefusal("{\"error\":{\"code\":403,\"errors\":[{\"domain\":\"global\",\"reason\":\"domainPolicy\"}]}}");

        startConsentAndAnswer(managed, "DRIVE_FILES", "blocked_by_organization");

        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", managed.workspaceId())).isZero();
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/revoke")))).as("a refused consent is not revoked").isEmpty();
    }

    @Test
    void aTokenGoogleNoLongerAcceptsIsWipedAndTheSameConnectionComesBackOnReconnecting() throws Exception {
        Member member = signIn("subject-reconnect");
        stubConsent(CALENDAR_SCOPE + " openid", "1//refresh-calendar-1", "ya29.access-calendar-1");
        stubUserInfo("sub-calendar", "person@example.org");
        startConsentAndAnswer(member, "CALENDAR_EVENTS", null);
        long connectionId = count("SELECT id FROM connector_connection WHERE workspace_id = ?", member.workspaceId());

        GOOGLE.stubFor(post(urlPathEqualTo("/token")).withRequestBody(containing("grant_type=refresh_token"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}")));
        assertThatThrownBy(() -> connectorService.use(member.workspaceId(), member.userId(), ConnectorAccess.CALENDAR_EVENTS))
                .isInstanceOf(ConnectionReconnectRequiredException.class)
                .extracting(e -> ((ConnectionReconnectRequiredException) e).reason())
                .isEqualTo(ReconnectReason.TOKEN_REJECTED);
        assertThat(text("SELECT state || '/' || reconnect_reason || '/' || (token_ciphertext IS NULL) FROM connector_connection WHERE id = ?",
                connectionId)).isEqualTo("RECONNECT_REQUIRED/TOKEN_REJECTED/true");

        GOOGLE.resetAll();
        stubConsent(CALENDAR_SCOPE + " openid", "1//refresh-calendar-2", "ya29.access-calendar-2");
        stubUserInfo("sub-calendar", "person@example.org");
        startConsentAndAnswer(member, "CALENDAR_EVENTS", null);
        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isEqualTo(1);
        assertThat(text("SELECT state FROM connector_connection WHERE id = ?", connectionId)).isEqualTo("ACTIVE");
    }

    @Test
    void disconnectingRevokesAtGoogleWipesEveryTokenAndClosesEveryGrantEvenWhenGoogleCannotBeReached() throws Exception {
        Member member = signIn("subject-disconnect");
        stubConsent(DRIVE_SCOPE, "1//refresh-drive-d", "ya29.access-drive-d");
        stubDriveAccount("perm-d", "person@example.org");
        startConsentAndAnswer(member, "DRIVE_FILES", null);
        stubConsent(CALENDAR_SCOPE + " openid", "1//refresh-calendar-d", "ya29.access-calendar-d");
        stubUserInfo("sub-d", "person@example.org");
        startConsentAndAnswer(member, "CALENDAR_EVENTS", null);
        long driveId = count("SELECT id FROM connector_connection WHERE workspace_id = ? AND access = 'DRIVE_FILES'", member.workspaceId());
        insertGrantAsOwner(member, driveId, "file-abc");

        GOOGLE.stubFor(post(urlPathEqualTo("/revoke")).withRequestBody(containing("refresh-drive-d"))
                .willReturn(aResponse().withStatus(200)));
        GOOGLE.stubFor(post(urlPathEqualTo("/revoke")).withRequestBody(containing("refresh-calendar-d"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(MockMvcRequestBuilders.post(connectionsPath(member) + "/google/disconnect").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.access == 'DRIVE_FILES')].providerRevocation").value("REVOKED"))
                .andExpect(jsonPath("$[?(@.access == 'CALENDAR_EVENTS')].providerRevocation").value("FAILED"))
                .andExpect(jsonPath("$[0].state").value("DISCONNECTED"));

        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ? AND token_ciphertext IS NOT NULL", member.workspaceId()))
                .as("wiped, including the one Google could not be told about").isZero();
        assertThat(text("SELECT revoked_reason FROM connector_resource_grant WHERE connection_id = ?", driveId)).isEqualTo("DISCONNECTED");
        assertThat(count("SELECT count(*) FROM audit_event WHERE workspace_id = ? AND action = 'CONNECTOR_DISCONNECTED'", member.workspaceId()))
                .isEqualTo(2);

        GOOGLE.resetRequests();
        mockMvc.perform(MockMvcRequestBuilders.post(connectionsPath(member) + "/google/disconnect").cookie(member.session()).with(csrf()))
                .andExpect(status().isOk());
        assertThat(GOOGLE.findAll(postRequestedFor(urlPathEqualTo("/revoke")))).as("disconnecting twice asks Google nothing").isEmpty();
    }

    @Test
    void deletingTheWorkspaceRemovesItsConnectionsAndOnlyThenAsksGoogleToForgetThem() throws Exception {
        Member member = signIn("subject-delete-workspace");
        stubConsent(DRIVE_SCOPE, "1//refresh-before-deletion", "ya29.access-before-deletion");
        stubDriveAccount("perm-del", null);
        startConsentAndAnswer(member, "DRIVE_FILES", null);
        long connectionId = count("SELECT id FROM connector_connection WHERE workspace_id = ?", member.workspaceId());
        insertGrantAsOwner(member, connectionId, "file-to-forget");
        GOOGLE.stubFor(post(urlPathEqualTo("/revoke")).willReturn(aResponse().withStatus(200)));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/workspaces/" + member.workspaceId() + "/deletions").cookie(member.session()).with(csrf())
                        .contentType("application/json").content("{\"scope\":\"WORKSPACE\"}"))
                .andExpect(status().isOk());

        assertThat(count("SELECT count(*) FROM connector_connection WHERE workspace_id = ?", member.workspaceId())).isZero();
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId())).isZero();
        GOOGLE.verify(postRequestedFor(urlPathEqualTo("/revoke")).withFormParam("token", equalTo("1//refresh-before-deletion")));
    }

    @Test
    void theDatabaseKeepsEachConnectionItsOwnPersonsAndEveryGrantReadOnly() throws Exception {
        Member owner = signIn("subject-rls-owner");
        Member stranger = signIn("subject-rls-stranger");
        stubConsent(DRIVE_SCOPE, "1//refresh-rls", "ya29.access-rls");
        stubDriveAccount("perm-rls", null);
        startConsentAndAnswer(owner, "DRIVE_FILES", null);
        long connectionId = count("SELECT id FROM connector_connection WHERE workspace_id = ?", owner.workspaceId());

        try (Connection api = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_api", API_PASSWORD)) {
            api.setAutoCommit(false);
            actAs(api, stranger.userId());
            assertThat(countOn(api, "SELECT count(*) FROM connector_connection WHERE id = " + connectionId)).as("invisible to anyone else").isZero();
            assertThat(api.prepareStatement("UPDATE connector_connection SET state = 'DISCONNECTED', disconnected_at = now(),"
                    + " provider_revocation = 'NOT_NEEDED', token_key_id = NULL, token_nonce = NULL, token_ciphertext = NULL WHERE id = "
                    + connectionId).executeUpdate()).isZero();
            assertThatThrownBy(() -> api.prepareStatement("INSERT INTO connector_connection (workspace_id, user_id, provider, access,"
                    + " account_id, granted_scopes, state, token_key_id, token_nonce, token_ciphertext, token_issued_at) VALUES ("
                    + owner.workspaceId() + ", " + owner.userId() + ", 'GOOGLE', 'CALENDAR_EVENTS', 'x', 's', 'ACTIVE', 'k',"
                    + " decode('000000000000000000000000', 'hex'), decode('00000000000000000000000000000000ff', 'hex'), now())").execute())
                    .as("a connection in someone else's name").isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            api.rollback();

            actAs(api, owner.userId());
            for (String table : List.of("connector_connection", "connector_resource_grant")) {
                assertThatThrownBy(() -> api.prepareStatement("DELETE FROM " + table + " WHERE workspace_id = " + owner.workspaceId()).execute())
                        .as("only the deletion routines ever remove a row of %s", table)
                        .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
                api.rollback();
                actAs(api, owner.userId());
            }
            assertThatThrownBy(() -> api.prepareStatement("UPDATE connector_connection SET account_id = 'someone-else' WHERE id = "
                    + connectionId).execute())
                    .as("which account a connection is for never changes").isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            api.rollback();
            actAs(api, owner.userId());
            assertThatThrownBy(() -> api.prepareStatement("INSERT INTO connector_resource_grant (workspace_id, connection_id, resource_type,"
                    + " external_id, allowed_operations, granted_by_user_id) VALUES (" + owner.workspaceId() + ", " + connectionId
                    + ", 'DRIVE_FILE', 'file-1', ARRAY['READ','WRITE'], " + owner.userId() + ")").execute())
                    .as("a grant that would allow a change").isInstanceOf(SQLException.class).hasMessageContaining("connector_resource_grant_read_only");
            api.rollback();
        }

        try (Connection owning = ownerConnection()) {
            assertThatThrownBy(() -> owning.prepareStatement("UPDATE connector_connection SET token_ciphertext = NULL WHERE id = "
                    + connectionId).execute())
                    .as("a usable connection without its token cannot exist").isInstanceOf(SQLException.class)
                    .hasMessageContaining("connector_connection_token_shape");
        }

        try (Connection worker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_worker", WORKER_PASSWORD)) {
            assertThatThrownBy(() -> worker.prepareStatement("SELECT token_ciphertext FROM connector_connection").executeQuery())
                    .as("the worker never reads a token").isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    // ---- helpers

    @Test
    void whileDriveIsNotOfferedAPickOrACopyIsRefusedBeforeGoogleIsAskedAndForgettingStillAnswers() throws Exception {
        Member member = signIn("subject-drive-not-offered");
        String drive = connectionsPath(member) + "/google/drive";

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/capabilities").cookie(member.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleConnectorAccess.length()").value(1))
                .andExpect(jsonPath("$.googleConnectorAccess[0]").value("CALENDAR_EVENTS"));
        for (String path : List.of(drive + "/picks", drive + "/imports", drive + "/files/1/forget")) {
            mockMvc.perform(MockMvcRequestBuilders.post(path).cookie(member.session()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(MockMvcRequestBuilders.post(drive + "/picks")
                        .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"returnTo\":\"https://elsewhere.example\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.post(drive + "/picks")
                        .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"returnTo\":\"/connections\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONNECTOR_NOT_CONFIGURED"));
        assertThat(pendingIn(member)).as("nothing is pending, so no answer can complete one").isNull();
        mockMvc.perform(MockMvcRequestBuilders.post(drive + "/imports")
                        .cookie(member.session()).with(csrf()).contentType("application/json").content("{\"documentId\":1,\"grantId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONNECTOR_NOT_CONFIGURED"));
        mockMvc.perform(MockMvcRequestBuilders.post(drive + "/files/999999/forget").cookie(member.session()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONNECTOR_RESOURCE_NOT_FOUND"));
        assertThat(GOOGLE.findAll(anyRequestedFor(urlMatching("/.*")))).as("Google is asked nothing").isEmpty();
    }

    @Test
    void anOrdinaryConsentThatComesBackWithFileIdsRecordsNoneOfThem() throws Exception {
        Member member = signIn("subject-consent-with-ids");
        stubConsent(DRIVE_SCOPE, "1//refresh-drive-ids", "ya29.access-drive-ids");
        stubDriveAccount("perm-ids", "ids@example.org");
        Map<String, String> connect = startConsent(member, "DRIVE_FILES", "/connections");

        assertThat(mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK)
                        .param("code", "4/stand-in").param("state", connect.get("state")).param("picked_file_ids", "fileA,fileB")
                        .cookie(member.session()))
                .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl())
                .isEqualTo("http://localhost:5173/connections?google=connected&access=drive_files");
        assertThat(count("SELECT count(*) FROM connector_resource_grant WHERE workspace_id = ?", member.workspaceId()))
                .as("only a pick's own answer is ever a pick").isZero();
    }

    @Test
    void aChoiceIsFoundAndForgottenOnlyByItsOwnPersonAndIsNeverReopened() throws Exception {
        Member member = signIn("subject-forget");
        Member other = signIn("subject-forget-other");
        stubConsent(DRIVE_SCOPE, "1//refresh-forget", "ya29.access-forget");
        stubDriveAccount("perm-forget", "forget@example.org");
        startConsentAndAnswer(member, "DRIVE_FILES", null);
        long connectionId = count("SELECT id FROM connector_connection WHERE workspace_id = ?", member.workspaceId());
        ResourceGrant chosen = resourceGrantRepository.grant(
                member.workspaceId(), member.userId(), connectionId, ResourceGrantType.DRIVE_FILE, "fileChosen1", "Minutes");

        assertThat(resourceGrantRepository.find(member.workspaceId(), member.userId(), chosen.id())).contains(chosen);
        assertThat(resourceGrantRepository.find(member.workspaceId(), other.userId(), chosen.id()))
                .as("another person does not see it").isEmpty();
        assertThat(resourceGrantRepository.revoke(member.workspaceId(), other.userId(), chosen.id(), GrantRevocationReason.REMOVED))
                .as("nor forget it").isEmpty();
        assertThat(resourceGrantRepository.find(member.workspaceId(), member.userId(), chosen.id()).orElseThrow().revokedAt()).isNull();

        ResourceGrant forgotten = resourceGrantRepository
                .revoke(member.workspaceId(), member.userId(), chosen.id(), GrantRevocationReason.REMOVED).orElseThrow();
        assertThat(forgotten.revokedAt()).isNotNull();
        assertThat(forgotten.revokedReason()).isEqualTo(GrantRevocationReason.REMOVED);
        assertThat(resourceGrantRepository.revoke(member.workspaceId(), member.userId(), chosen.id(), GrantRevocationReason.REMOVED))
                .as("forgetting it again changes nothing").isEmpty();
        assertThat(resourceGrantRepository.findOpen(member.workspaceId(), member.userId(), connectionId)).isEmpty();

        try (Connection api = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "brownie_api", API_PASSWORD)) {
            api.setAutoCommit(false);
            actAs(api, member.userId());
            assertThat(api.prepareStatement("UPDATE connector_resource_grant SET revoked_at = NULL, revoked_reason = NULL"
                    + " WHERE id = " + chosen.id()).executeUpdate())
                    .as("a forgotten choice is never opened again").isZero();
            api.rollback();
        }
        assertThat(text("SELECT revoked_reason FROM connector_resource_grant WHERE id = ?", chosen.id())).isEqualTo("REMOVED");
        // Choosing the same file again is a new choice, not the old one reopened.
        ResourceGrant again = resourceGrantRepository.grant(
                member.workspaceId(), member.userId(), connectionId, ResourceGrantType.DRIVE_FILE, "fileChosen1", "Minutes");
        assertThat(again.id()).isNotEqualTo(chosen.id());
        assertThat(again.revokedAt()).isNull();
    }

    private Map<String, String> startConsent(Member member, String access, String returnTo) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(connectionsPath(member) + "/google")
                        .cookie(member.session()).with(csrf())
                        .contentType("application/json")
                        .content("{\"access\":\"" + access + "\",\"returnTo\":\"" + returnTo + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return queryOf(JSON.readTree(result.getResponse().getContentAsString()).get("authorizationUrl").asString());
    }

    private void startConsentAndAnswer(Member member, String access, String expectedFailure) throws Exception {
        Map<String, String> query = startConsent(member, access, "/connections");
        String location = callback(member, "code-" + access, query.get("state"));
        if (expectedFailure == null) {
            assertThat(location).contains("google=connected");
        } else {
            assertThat(location).endsWith("reason=" + expectedFailure);
        }
    }

    private String callback(Member member, String code, String state) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK)
                        .param("code", code).param("state", state).cookie(member.session()))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private String callbackWithError(Member member, String error) throws Exception {
        String state = pendingIn(member).state();
        return mockMvc.perform(MockMvcRequestBuilders.get(CALLBACK)
                        .param("error", error).param("state", state).cookie(member.session()))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private static void stubConsent(String scope, String refreshToken, String accessToken) {
        GOOGLE.stubFor(post(urlPathEqualTo("/token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":3599,"refresh_token":"%s","token_type":"Bearer","scope":"%s"}
                """.formatted(accessToken, refreshToken, scope))));
    }

    private static void stubDriveAccount(String permissionId, String email) {
        String user = email == null ? "{\"permissionId\":\"" + permissionId + "\"}"
                : "{\"permissionId\":\"" + permissionId + "\",\"emailAddress\":\"" + email + "\"}";
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/about")).willReturn(okJson("{\"user\":" + user + "}")));
    }

    private static void stubDriveRefusal(String body) {
        GOOGLE.stubFor(get(urlPathEqualTo("/drive/v3/about"))
                .willReturn(aResponse().withStatus(403).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void stubUserInfo(String subject, String email) {
        GOOGLE.stubFor(get(urlPathEqualTo("/userinfo")).willReturn(okJson("{\"sub\":\"" + subject + "\",\"email\":\"" + email + "\"}")));
    }

    private PendingConsent pendingIn(Member member) {
        String sessionId = new String(Base64.getDecoder().decode(member.session().getValue()), StandardCharsets.UTF_8);
        return (PendingConsent) sessionRepository.findById(sessionId).getAttribute(PendingConsent.SESSION_ATTRIBUTE);
    }

    private void insertGrantAsOwner(Member member, long connectionId, String fileId) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO connector_resource_grant (workspace_id, connection_id, resource_type, external_id, granted_by_user_id)"
                        + " VALUES (?, ?, 'DRIVE_FILE', ?, ?)")) {
            insert.setLong(1, member.workspaceId());
            insert.setLong(2, connectionId);
            insert.setString(3, fileId);
            insert.setLong(4, member.userId());
            insert.executeUpdate();
        }
    }

    private Member signIn(String subject) {
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
        return new Member(cookie, workspaceId, userId);
    }

    private static <S extends Session> S createAuthenticatedSession(FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private record Member(Cookie session, long workspaceId, long userId) {
    }

    private static String connectionsPath(Member member) {
        return "/api/v1/workspaces/" + member.workspaceId() + "/connections";
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> values = new HashMap<>();
        UriComponentsBuilder.fromUriString(url).build().getQueryParams()
                .forEach((name, list) -> values.put(name, URLDecoder.decode(list.getFirst(), StandardCharsets.UTF_8)));
        return values;
    }

    private static Map<String, String> formOf(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int equals = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void actAs(Connection connection, long userId) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            context.setString(1, String.valueOf(userId));
            context.executeQuery();
        }
    }

    private static long countOn(Connection connection, String sql) throws SQLException {
        try (ResultSet rs = connection.prepareStatement(sql).executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
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

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath().getParent().getParent().resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    private static WireMockServer startedGoogle() {
        WireMockServer server = new WireMockServer(0);
        server.start();
        return server;
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
