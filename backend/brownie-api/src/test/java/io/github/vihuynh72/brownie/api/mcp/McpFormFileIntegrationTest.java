package io.github.vihuynh72.brownie.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code add_form_file}: ChatGPT's attached file, fetched only from the
 * chat app's own host, run through the same intake a link upload gets,
 * and answered with the same state shape {@code get_form_upload} gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class McpFormFileIntegrationTest extends McpIntegrationTestSupport {

    private static final String ISSUER = "https://issuer-mcp-form-file";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, DB);
    }

    @MockitoBean
    FormFileFetcher fetcher;

    @Test
    void theToolAdvertisesItsFileParameterForTheChatApp() throws Exception {
        Browser browser = signIn("subject-file-meta", ISSUER);
        String token = issueToken(browser, "meta", "[\"fill\"]").token();
        assertThat(mcpRaw(token, initialize()).statusCode()).isEqualTo(200);

        JsonNode tools = readJson(mcpRaw(token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")).get("result").get("tools");
        JsonNode mine = null;
        for (JsonNode t : tools) {
            if ("add_form_file".equals(t.get("name").asText())) {
                mine = t;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("_meta").get("openai/fileParams").get(0).asText()).isEqualTo("form");
    }

    @Test
    void anAttachedFormBecomesATemplate() throws Exception {
        Browser browser = signIn("subject-file-ok", ISSUER);
        String token = issueToken(browser, "ok", "[\"fill\",\"read\"]").token();
        assertThat(mcpRaw(token, initialize()).statusCode()).isEqualTo(200);
        when(fetcher.fetch(any())).thenReturn(minimalDocx());

        JsonNode out = readJson(mcpRaw(token, call(Map.of(
                "file_id", "file-1",
                "download_url", "https://files.oaiusercontent.com/abc123",
                "name", "application.docx",
                "mime_type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))))
                .get("result").get("structuredContent");
        assertThat(out.get("state").asText()).isEqualTo("READY");
        assertThat(out.get("template_id").asLong()).isPositive();

        long uploadId = out.get("upload_id").asLong();
        JsonNode state = readJson(mcpRaw(token, callGetFormUpload(uploadId)))
                .get("result").get("structuredContent");
        assertThat(state.get("state").asText()).isEqualTo("READY");
        assertThat(state.get("template_id").asLong()).isEqualTo(out.get("template_id").asLong());
    }

    @Test
    void onlyTheChatAppsOwnHostIsEverAsked() throws Exception {
        Browser browser = signIn("subject-file-hosts", ISSUER);
        String token = issueToken(browser, "hosts", "[\"fill\"]").token();
        assertThat(mcpRaw(token, initialize()).statusCode()).isEqualTo(200);

        for (String bad : new String[] {
            "https://localhost/x", "http://files.oaiusercontent.com/x",
            "https://127.0.0.1/x", "file:///etc/passwd", "https://evil.com/x",
            "https://files.oaiusercontent.com.evil.com/x"
        }) {
            JsonNode answer = readJson(mcpRaw(token, call(Map.of(
                    "file_id", "f", "download_url", bad, "name", "x.docx"))));
            assertThat(answer.get("result").get("isError").asBoolean()).as(bad).isTrue();
        }
        verify(fetcher, never()).fetch(any());
    }

    @Test
    void aStringWhereTheObjectBelongsIsExplainedNotCrashedOn() throws Exception {
        Browser browser = signIn("subject-file-string", ISSUER);
        String token = issueToken(browser, "str", "[\"fill\"]").token();
        assertThat(mcpRaw(token, initialize()).statusCode()).isEqualTo(200);

        JsonNode answer = readJson(mcpRaw(token,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"add_form_file\","
                        + "\"arguments\":{\"form\":\"chat_upload:file-9\"}}}"));
        assertThat(answer.get("result").get("isError").asBoolean()).isTrue();
        assertThat(answer.get("result").toString()).contains("attach it in the desktop or web app");
    }

    private static String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
    }

    private static String call(Map<String, Object> form) throws Exception {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"add_form_file\",\"arguments\":{\"form\":"
                + JSON.writeValueAsString(form) + "}}}";
    }

    private static String callGetFormUpload(long uploadId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_form_upload\",\"arguments\":{\"upload_id\":"
                + uploadId + "}}}";
    }

    private static byte[] minimalDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Name: ______");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }
}
