package io.github.vihuynh72.brownie.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code brownie.mcp.enabled} off the feature is not muted, it is
 * absent: the endpoint, the metadata document, the download route and
 * the grant routes have no mapping, and capabilities tell a client so.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "brownie.mcp.enabled=false",
        "brownie.mcp.token-key="})
@DockerTest
class McpDisabledIntegrationTest extends McpIntegrationTestSupport {

    private static final String ISSUER = "https://issuer-mcp-off";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, DB);
    }

    @Test
    void nothingMcpIsMappedAndCapabilitiesSaySo() throws Exception {
        Browser browser = signIn("subject-mcp-off", ISSUER);

        // Signed in, so a 404 can only mean the route is not there at all.
        get(browser, "/mcp", 404);
        get(browser, "/.well-known/oauth-protected-resource/mcp", 404);
        get(browser, "/api/v1/mcp-downloads/anything", 404);
        get(browser, "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/grants", 404);
        postJson(browser,
                "/api/v1/workspaces/" + browser.workspaceId() + "/mcp/personal-tokens",
                "{\"name\":\"x\",\"scopes\":[\"read\"]}", 404);

        JsonNode capabilities = readJson(get(browser, "/api/v1/capabilities", 200));
        assertThat(capabilities.get("mcpEnabled").asBoolean()).isFalse();
        assertThat(capabilities.get("mcpServerUrl").isNull()).isTrue();
    }
}
