package io.github.vihuynh72.brownie.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.workspace.Workspace;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plumbing every MCP integration test stands on: real HTTP to a
 * random-port server backed by real Postgres/Azurite/ClamAV, a signed-in
 * "browser" minted straight into the session store (the same shortcut the
 * MockMvc suites take), the session grant routes that issue personal
 * tokens, and a real MCP Java SDK client speaking JSON-RPC to {@code /mcp}.
 */
abstract class McpIntegrationTestSupport {

    static final String API_PASSWORD = "brownie_api_local_only";
    static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    @Autowired
    UserIdentityRepository userIdentityRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    static void datasourceProperties(DynamicPropertyRegistry registry, TestDatabase database) {
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", database::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("brownie.storage.local-connection", database::azuriteConnectionString);
        registry.add("brownie.security.clamav.host", SharedContainers::clamAvHost);
        registry.add("brownie.security.clamav.port", SharedContainers::clamAvPort);
    }

    /** A signed-in browser: a real Spring Session row the main chain trusts, plus its CSRF token for mutations. */
    Browser signIn(String subject, String issuer) {
        userIdentityRepository.recordLogin(issuer, subject, null, null);
        var identity = userIdentityRepository.findByIssuerAndSubject(issuer, subject).orElseThrow();
        Workspace workspace = workspaceRepository.ensurePersonalWorkspace(identity.id());

        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, issuer)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        Session session = createAuthenticatedSession(sessionRepository, context);
        String sessionCookie = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        return new Browser(sessionCookie, identity.id(), workspace.id());
    }

    /** Issues a personal token through the real grant route; the plaintext is asserted on later in the test. */
    IssuedToken issueToken(Browser browser, String name, String scopesJson) throws Exception {
        JsonNode issued = readJson(postJson(
                browser,
                "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"" + name + "\",\"scopes\":" + scopesJson + "}",
                201));
        return new IssuedToken(issued.get("grantId").asLong(), issued.get("token").asText());
    }

    /** A connected SDK client: initialized against the real server with the grant's token. */
    McpSyncClient mcpClient(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(url(""))
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    /** The structured payload of a successful tool call, or the refusal map for an isError one. */
    JsonNode toolResult(McpSyncClient client, String tool, Map<String, Object> arguments) throws Exception {
        return toolResult(callTool(client, tool, arguments));
    }

    /**
     * The structured payload of any finished call. A successful result
     * must also carry its data in a text block: some chat apps hand the
     * model only the text content, so without it the model sees the
     * summary line and none of the fields.
     */
    static JsonNode toolResult(McpSchema.CallToolResult result) throws Exception {
        if (result.structuredContent() != null) {
            JsonNode structured = JSON.readTree(JSON.writeValueAsString(result.structuredContent()));
            if (!Boolean.TRUE.equals(result.isError())) {
                assertStructuredContentEchoedAsText(result, structured);
            }
            return structured;
        }
        JsonNode first = JSON.readTree(JSON.writeValueAsString(result.content().get(0)));
        return JSON.readTree(first.get("text").asText());
    }

    private static void assertStructuredContentEchoedAsText(McpSchema.CallToolResult result, JsonNode structured)
            throws Exception {
        JsonNode first = JSON.readTree(JSON.writeValueAsString(result.content().get(0)));
        JsonNode text = first.get("text");
        JsonNode echoed = null;
        if (text != null && text.isTextual()) {
            try {
                echoed = JSON.readTree(text.asText());
            } catch (Exception notJson) {
                // Not the JSON block.
            }
        }
        assertThat(echoed)
                .as("the FIRST text block must carry the same data as structuredContent "
                        + "(a chat app that gives the model only the first text block otherwise sees none of it)")
                .isEqualTo(structured);
    }

    boolean isRefusal(McpSchema.CallToolResult result) {
        return Boolean.TRUE.equals(result.isError());
    }

    McpSchema.CallToolResult callTool(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(tool, arguments));
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

    HttpResponse<String> postJson(Browser browser, String path, String body, int expectedStatus) throws Exception {
        if (browser.xsrfToken() == null) {
            browser.setXsrfToken(xsrfToken(browser));
        }
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Cookie", "SESSION=" + browser.sessionCookie() + "; XSRF-TOKEN=" + browser.xsrfToken())
                        .header("X-XSRF-TOKEN", browser.xsrfToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s -> %s", path, response.body())
                .isEqualTo(expectedStatus);
        return response;
    }

    HttpResponse<String> get(Browser browser, String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Cookie", "SESSION=" + browser.sessionCookie())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s -> %s", path, response.body()).isEqualTo(expectedStatus);
        return response;
    }

    HttpResponse<String> getAnonymous(String absoluteOrRelativeUrl, int expectedStatus) throws Exception {
        String target = absoluteOrRelativeUrl.startsWith("http") ? absoluteOrRelativeUrl : url(absoluteOrRelativeUrl);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(target)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", target).isEqualTo(expectedStatus);
        return response;
    }

    HttpResponse<String> mcpRaw(String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url("/mcp")))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode readJson(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    static Connection ownerConnection(TestDatabase database) throws Exception {
        return DriverManager.getConnection(database.jdbcUrl(), "brownie_migration", MIGRATION_PASSWORD);
    }

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    /** The session pair an HTTP request needs: the cookie and the CSRF token the spa filter expects with it. */
    static final class Browser {
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

    record IssuedToken(long grantId, String token) {
    }
}
