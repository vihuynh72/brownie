package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.api.template.BuiltInTemplateProvisioningService;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP boundary contract over real HTTP against real Postgres: the
 * grant routes a session uses to mint and retire personal tokens, the
 * bearer challenge and protected-resource document the MCP endpoint
 * answers, and the scope refusal an under-scoped call earns. No MCP tool
 * needs the model here, so the whole file is protocol plumbing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class McpContractIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";
    private static final String ISSUER = "https://issuer-mcp-contract";

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

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Autowired
    private BuiltInTemplateProvisioningService builtInTemplateProvisioningService;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void issuingListingAndRevokingAPersonalTokenWorksThroughTheSessionRoutes() throws Exception {
        Browser browser = signIn("subject-mcp-grants");

        JsonNode issued = readJson(postJson(
                browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Claude Code\",\"scopes\":[\"read\",\"fill\",\"export\"]}", 201));
        long grantId = issued.get("grantId").asLong();
        String token = issued.get("token").asText();
        assertThat(token).startsWith("brownie_pat_");

        JsonNode grants = readJson(get(browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/grants", 200));
        assertThat(grants).hasSize(1);
        JsonNode listed = grants.get(0);
        assertThat(listed.get("id").asLong()).isEqualTo(grantId);
        assertThat(listed.get("kind").asText()).isEqualTo("PERSONAL_TOKEN");
        assertThat(listed.get("name").asText()).isEqualTo("Claude Code");
        assertThat(listed.get("scopes")).extracting(JsonNode::asText).containsExactlyInAnyOrder("read", "fill", "export");
        // The listing is safe to keep on screen: no raw token, no hash.
        assertThat(listed.get("tokenHint").asText()).hasSize(4);
        assertThat(listed.toString()).doesNotContain(token);
        assertThat(listed.get("revokedAt").isNull()).isTrue();

        postJson(browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/grants/" + grantId + "/revoke", null, 204);
        JsonNode after = readJson(get(browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/grants", 200));
        assertThat(after.get(0).get("revokedAt").isNull()).isFalse();
    }

    @Test
    void onlyTheTokensHashAndHintAreStored() throws Exception {
        Browser browser = signIn("subject-mcp-hash");
        JsonNode issued = readJson(postJson(
                browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Cursor\",\"scopes\":[\"read\"]}", 201));
        String token = issued.get("token").asText();
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));

        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT token_sha256, token_hint FROM mcp_grant WHERE id = ?")) {
            statement.setLong(1, issued.get("grantId").asLong());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(expectedHash);
                assertThat(rs.getString(1)).isNotEqualTo(token);
                assertThat(rs.getString(2)).isEqualTo(token.substring(token.length() - 4));
            }
        }
        // The whole row contains nowhere the presented secret.
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM mcp_grant WHERE token_sha256 = ? OR token_hint = ?")) {
            statement.setString(1, token);
            statement.setString(2, token);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    void anIssuedTokenCallsMcpAndRevokingItStopsItAtOnce() throws Exception {
        Browser browser = signIn("subject-mcp-revoke");
        JsonNode issued = readJson(postJson(
                browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Revocable\",\"scopes\":[\"read\"]}", 201));
        String token = issued.get("token").asText();

        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);

        postJson(browser,
                "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/grants/" + issued.get("grantId").asLong() + "/revoke",
                null, 204);

        HttpResponse<String> afterRevoke = mcpInitialize(token);
        assertThat(afterRevoke.statusCode()).isEqualTo(401);
        assertThat(afterRevoke.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("Bearer ")
                .contains("resource_metadata=\"")
                .contains("/.well-known/oauth-protected-resource");
    }

    @Test
    void aBearerCallThatAlsoCarriesTheBrowserSessionLeavesThatSessionAlone() throws Exception {
        Browser browser = signIn("subject-mcp-session");
        JsonNode issued = readJson(postJson(
                browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Mixed credentials\",\"scopes\":[\"read\"]}", 201));
        String token = issued.get("token").asText();

        // A request holding both a bearer token and the browser's session
        // cookie authenticates as the grant alone: answering it must not
        // rotate, replace or otherwise touch the browser session the
        // cookie names, or every other request that cookie makes breaks.
        for (String body : List.of(
                initializeBody("2025-06-18"),
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}")) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url("/mcp")))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .header("Authorization", "Bearer " + token)
                            .header("Cookie", "SESSION=" + browser.sessionCookie())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().allValues("Set-Cookie"))
                    .as("no answer to a bearer call may carry a session")
                    .noneMatch(value -> value.startsWith("SESSION="));
        }

        // The session the calls left alone still answers a session route.
        get(browser, "/api/v1/me", 200);
    }

    @Test
    void mcpWithoutATokenOrWithAnUnknownOneAnswers401WithTheChallenge() throws Exception {
        signIn("subject-mcp-challenge");

        for (String authorization : new String[] {null, "Bearer not-a-real-token", "Bearer brownie_pat_deadbeef"}) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/mcp")))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(initializeBody("2025-06-18")));
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("authorization: %s", authorization).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow())
                    .startsWith("Bearer ")
                    .contains("resource_metadata=\"")
                    .contains("/.well-known/oauth-protected-resource/mcp");
        }
    }

    @Test
    void theProtectedResourceDocumentIsPublicAndNamesTheEndpoint() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource")) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            JsonNode document = JSON.readTree(response.body());
            assertThat(document.get("resource").asText()).endsWith("/mcp");
            assertThat(document.get("authorization_servers").get(0).asText()).isNotBlank();
            assertThat(document.get("scopes_supported"))
                    .extracting(JsonNode::asText)
                    .containsExactlyInAnyOrder("read", "fill", "export");
            assertThat(document.get("bearer_methods_supported"))
                    .extracting(JsonNode::asText)
                    .containsExactly("header");
        }
    }

    @Test
    void theResourceAndAuthorizationServerAreTheConfiguredOriginWhateverTheRequestSays() throws Exception {
        // A request that arrives under a different host -- here the same
        // port reached through 127.0.0.1 instead of localhost -- must not
        // move the resource the document names: the authorization server
        // enforces exactly the configured <public origin>/mcp, so a
        // request-derived value would send clients asking for a resource
        // the server refuses.
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource/mcp"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = JSON.readTree(response.body());
        assertThat(document.get("resource").asText()).isEqualTo("http://localhost:8081/mcp");
        assertThat(document.get("authorization_servers").get(0).asText()).isEqualTo("http://localhost:8081");
    }

    @Test
    void aTokenMissingTheToolScopeGets403WithTheInsufficientScopeHeaderAndARecordedRefusal() throws Exception {
        Browser browser = signIn("subject-mcp-scope");
        JsonNode issued = readJson(postJson(
                browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Read only\",\"scopes\":[\"read\"]}", 201));
        String token = issued.get("token").asText();
        long grantId = issued.get("grantId").asLong();

        // A read-scoped token may list tools but not call a fill tool.
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        HttpResponse<String> refused = mcpCall(token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"start_document\",\"arguments\":{\"template_id\":1}}}");
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().firstValue("WWW-Authenticate").orElseThrow())
                .isEqualTo("Bearer error=\"insufficient_scope\", scope=\"fill\"");

        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT outcome, code FROM mcp_tool_call WHERE grant_id = ? AND tool = 'start_document'")) {
            statement.setLong(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("REFUSED");
                assertThat(rs.getString(2)).isEqualTo("INSUFFICIENT_SCOPE");
            }
        }
    }

    @Test
    void aRefusalForADocumentThatDoesNotExistIsStillRecordedWithNoDocument() throws Exception {
        Browser browser = signIn("subject-mcp-null-doc");
        JsonNode issued = readJson(postJson(
                browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Records\",\"scopes\":[\"read\",\"fill\",\"export\"]}", 201));
        String token = issued.get("token").asText();
        long grantId = issued.get("grantId").asLong();

        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        JsonNode refused = readJson(mcpCall(token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"export_document\","
                        + "\"arguments\":{\"document_id\":999999999,\"format\":\"docx\"}}}"));
        assertThat(refused.get("result").get("isError").asBoolean()).isTrue();

        // The named document never existed, so the record must carry no
        // document id -- storing the caller's number would either violate
        // the workspace-scoped foreign key or point at a row that is not
        // this workspace's.
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT outcome, code, document_id FROM mcp_tool_call WHERE grant_id = ?")) {
            statement.setLong(1, grantId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("REFUSED");
                assertThat(rs.getString(2)).isEqualTo("NOT_FOUND");
                assertThat(rs.getObject(3)).isNull();
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void aRefusalForAnotherWorkspacesDocumentIsRecordedWithoutThatDocument() throws Exception {
        Browser owner = signIn("subject-mcp-xdoc-owner");
        Browser caller = signIn("subject-mcp-xdoc-caller");
        builtInTemplateProvisioningService.ensureBuiltInTemplates(owner.workspaceId(), owner.userId());

        // Mint a real document in the owner's workspace over MCP itself.
        JsonNode ownerGrant = readJson(postJson(
                owner, "/api/v1/workspaces/" + owner.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Owner\",\"scopes\":[\"read\",\"fill\",\"export\"]}", 201));
        String ownerToken = ownerGrant.get("token").asText();
        assertThat(mcpInitialize(ownerToken).statusCode()).isEqualTo(200);
        JsonNode templates = readJson(mcpCall(ownerToken,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_templates\",\"arguments\":{}}}"));
        long templateId = templates.get("result").get("structuredContent")
                .get("templates").get(0).get("template_id").asLong();
        JsonNode started = readJson(mcpCall(ownerToken,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"start_document\","
                        + "\"arguments\":{\"template_id\":" + templateId + "}}}"));
        long ownerDocumentId = started.get("result").get("structuredContent").get("document_id").asLong();

        // The caller's grant names that foreign document id; the refusal
        // is recorded in the caller's workspace but must not pin the row
        // to a document the caller cannot see.
        JsonNode callerGrant = readJson(postJson(
                caller, "/api/v1/workspaces/" + caller.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Caller\",\"scopes\":[\"read\",\"fill\",\"export\"]}", 201));
        String callerToken = callerGrant.get("token").asText();
        long callerGrantId = callerGrant.get("grantId").asLong();
        assertThat(mcpInitialize(callerToken).statusCode()).isEqualTo(200);
        JsonNode refused = readJson(mcpCall(callerToken,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"export_document\","
                        + "\"arguments\":{\"document_id\":" + ownerDocumentId + ",\"format\":\"docx\"}}}"));
        assertThat(refused.get("result").get("isError").asBoolean()).isTrue();

        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT workspace_id, outcome, code, document_id FROM mcp_tool_call WHERE grant_id = ?")) {
            statement.setLong(1, callerGrantId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(caller.workspaceId());
                assertThat(rs.getString(2)).isEqualTo("REFUSED");
                assertThat(rs.getString(3)).isEqualTo("NOT_FOUND");
                assertThat(rs.getObject(4)).isNull();
                assertThat(rs.next()).isFalse();
            }
        }
        // The other workspace's document id must not appear in any record.
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM mcp_tool_call WHERE document_id = ?")) {
            statement.setLong(1, ownerDocumentId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                // Only the owner's own start_document call may name it.
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void anUnsupportedProtocolVersionIsRefused() throws Exception {
        Browser browser = signIn("subject-mcp-version");
        JsonNode issued = readJson(postJson(
                browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"Versions\",\"scopes\":[\"read\"]}", 201));
        String token = issued.get("token").asText();

        HttpResponse<String> response = mcpCall(token, initializeBody("1999-01-01-nonsense"));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        // Protocol negotiation: the server answers with a version it
        // actually speaks, never the unsupported one the client asked for.
        // A client that cannot talk the counter-offer ends the session
        // itself, so "refused" here is "never echoed back as agreed".
        assertThat(body.get("result").get("protocolVersion").asText())
                .isNotEqualTo("1999-01-01-nonsense")
                .isIn("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    }

    @Test
    void grantRoutesRequireAManageWorkspaceSessionAndRefuseBadInput() throws Exception {
        Browser owner = signIn("subject-mcp-grant-owner");
        Browser outsider = signIn("subject-mcp-grant-outsider");

        // Another workspace's grant surface is forbidden: the capability
        // check answers before any row could be named.
        get(outsider, "/api/v1/workspaces/" + owner.workspaceId() + "/mcp/grants", 403);
        postJson(outsider, "/api/v1/workspaces/" + owner.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"not mine\",\"scopes\":[\"read\"]}", 403);

        // Bad arguments are refused, not recorded.
        postJson(owner, "/api/v1/workspaces/" + owner.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"\",\"scopes\":[\"read\"]}", 400);
        postJson(owner, "/api/v1/workspaces/" + owner.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"x\",\"scopes\":[\"admin\"]}", 400);
        postJson(owner, "/api/v1/workspaces/" + owner.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"x\",\"scopes\":[]}", 400);
        postJson(owner, "/api/v1/workspaces/" + owner.workspaceId() + "/mcp/grants/999999/revoke", null, 404);
    }

    // -- plumbing ---------------------------------------------------------------

    /** A signed-in browser: a real Spring Session row the main chain trusts, plus its CSRF token for mutations. */
    private Browser signIn(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        var identity = userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(identity.id());

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
        String sessionCookie = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        return new Browser(sessionCookie, identity.id(), workspace.id());
    }

    /** Reads any protected page once so the CSRF cookie lands, then remembers it for mutations. */
    private String xsrfToken(Browser browser) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url("/api/v1/me")))
                        .header("Cookie", "SESSION=" + browser.sessionCookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        for (String setCookie : response.headers().allValues("Set-Cookie")) {
            if (setCookie.startsWith("XSRF-TOKEN=")) {
                return setCookie.substring("XSRF-TOKEN=".length()).split(";")[0];
            }
        }
        throw new AssertionError("No XSRF-TOKEN cookie in " + response.headers().map());
    }

    private HttpResponse<String> postJson(Browser browser, String path, String body, int expectedStatus) throws Exception {
        if (browser.xsrfToken() == null) {
            browser.setXsrfToken(xsrfToken(browser));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Cookie", "SESSION=" + browser.sessionCookie() + "; XSRF-TOKEN=" + browser.xsrfToken())
                .header("X-XSRF-TOKEN", browser.xsrfToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s -> %s", path, response.body())
                .isEqualTo(expectedStatus);
        return response;
    }

    private HttpResponse<String> get(Browser browser, String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Cookie", "SESSION=" + browser.sessionCookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s -> %s", path, response.body()).isEqualTo(expectedStatus);
        return response;
    }

    private HttpResponse<String> mcpInitialize(String token) throws Exception {
        return mcpCall(token, initializeBody("2025-06-18"));
    }

    private HttpResponse<String> mcpCall(String token, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(url("/mcp")))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String initializeBody(String protocolVersion) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                + protocolVersion + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
    }

    private JsonNode readJson(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(DB.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    /** The session pair an HTTP request needs: the cookie and the CSRF token the spa filter expects with it. */
    private static final class Browser {
        private final String sessionCookie;
        private final long userId;
        private final long workspaceId;
        private String xsrfToken;

        private Browser(String sessionCookie, long userId, long workspaceId) {
            this.sessionCookie = sessionCookie;
            this.userId = userId;
            this.workspaceId = workspaceId;
        }

        String sessionCookie() {
            return sessionCookie;
        }

        long userId() {
            return userId;
        }

        long workspaceId() {
            return workspaceId;
        }

        String xsrfToken() {
            return xsrfToken;
        }

        void setXsrfToken(String xsrfToken) {
            this.xsrfToken = xsrfToken;
        }
    }
}
