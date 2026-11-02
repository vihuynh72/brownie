package io.github.vihuynh72.brownie.api.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The form-intake contract end to end: a tool mints a one-time link, the
 * anonymous route spends it exactly once, and the row it leaves is the
 * only record of what happened -- the token itself is never stored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class McpFormUploadIntegrationTest extends McpIntegrationTestSupport {

    private static final String ISSUER = "https://issuer-mcp-form-upload";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasourceProperties(registry, DB);
        registry.add("brownie.render.image",
                () -> java.util.Optional.ofNullable(System.getProperty("brownie.render.image", System.getenv("BROWNIE_RENDER_IMAGE")))
                        .filter(image -> !image.isBlank()).orElse("brownie-spike-renderer:pinned"));
    }

    @Test
    void aTokenAsksForALinkAndTheLinkBecomesATemplate() throws Exception {
        Browser browser = signIn("subject-mcp-upload", ISSUER);
        String token = issueToken(browser, "u1", "[\"fill\",\"read\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);

        JsonNode asked = readJson(mcpRaw(token, call("request_form_upload",
                "\"file_name\":\"application.docx\""))).get("result").get("structuredContent");
        String link = asked.get("upload_url").asText();
        String uploadToken = link.substring(link.lastIndexOf('/') + 1);
        assertThat(uploadToken).startsWith("brownie_up_");
        long uploadId = asked.get("upload_id").asLong();

        // The page's own state read, before the file arrives.
        HttpResponse<String> peeked = http.send(HttpRequest.newBuilder(
                URI.create(url("/api/v1/mcp-uploads/" + uploadToken))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(peeked.statusCode()).isEqualTo(200);
        assertThat(peeked.body()).contains("PENDING");

        HttpResponse<String> posted = postUpload(URI.create(url("/api/v1/mcp-uploads/" + uploadToken)),
                "application.docx", minimalDocx());
        assertThat(posted.statusCode()).isEqualTo(200);
        JsonNode received = readJson(posted);
        assertThat(received.get("state").asText()).isEqualTo("READY");
        long templateId = received.get("template_id").asLong();
        assertThat(templateId).isPositive();

        JsonNode state = readJson(mcpRaw(token, call("get_form_upload",
                "\"upload_id\":" + uploadId))).get("result").get("structuredContent");
        assertThat(state.get("state").asText()).isEqualTo("READY");
        assertThat(state.get("template_id").asLong()).isEqualTo(templateId);
        assertThat(state.get("fill_spots")).isNotNull();
        // The form's blanks are real spots now: each label line yielded one.
        assertThat(state.get("fill_spots").size()).isGreaterThanOrEqualTo(7);
        for (JsonNode spot : state.get("fill_spots")) {
            assertThat(spot.get("label").asText()).isNotBlank();
            assertThat(spot.get("label").asText()).isNotEqualTo(spot.get("field_id").asText());
        }
    }

    @Test
    void aFillablePdfUploadGivesOneSpotPerField() throws Exception {
        Browser browser = signIn("subject-mcp-upload-pdf", ISSUER);
        String token = issueToken(browser, "u3", "[\"fill\",\"read\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        JsonNode issued = readJson(mcpRaw(token, call("request_form_upload", "")))
                .get("result").get("structuredContent");
        long uploadId = issued.get("upload_id").asLong();
        String uploadToken = issued.get("upload_url").asText();
        uploadToken = uploadToken.substring(uploadToken.lastIndexOf('/') + 1);

        HttpResponse<String> posted = postUpload(URI.create(url("/api/v1/mcp-uploads/" + uploadToken)),
                "volunteer.pdf", io.github.vihuynh72.brownie.api.document.pdf.PdfFormFixtures.fillableForm());


        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(readJson(posted).get("spot_count").asInt()).isGreaterThanOrEqualTo(5);

        JsonNode state = readJson(mcpRaw(token, call("get_form_upload",
                "\"upload_id\":" + uploadId))).get("result").get("structuredContent");
        assertThat(state.get("state").asText()).isEqualTo("READY");
    }

    @Test
    void anExpiredOrSpentOrUnknownLinkIsIndistinguishable() throws Exception {
        Browser browser = signIn("subject-mcp-upload-404", ISSUER);
        String token = issueToken(browser, "u2", "[\"fill\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        String spent = linkToken(mcpRaw(token, call("request_form_upload", "")));

        assertThat(postUpload(URI.create(url("/api/v1/mcp-uploads/" + spent)),
                "a.docx", minimalDocx()).statusCode()).isEqualTo(200);
        // Once spent, the same link is unknown -- same status, same body.
        HttpResponse<String> again = postUpload(URI.create(url("/api/v1/mcp-uploads/" + spent)),
                "a.docx", minimalDocx());
        assertThat(again.statusCode()).isEqualTo(404);
        HttpResponse<String> unknown = postUpload(
                URI.create(url("/api/v1/mcp-uploads/brownie_up_" + "0".repeat(43))),
                "a.docx", minimalDocx());
        assertThat(unknown.statusCode()).isEqualTo(404);
        // Same answer shape either way: the only difference is which
        // URL was asked for, never what the link once was.
        JsonNode againBody = readJson(again);
        JsonNode unknownBody = readJson(unknown);
        assertThat(againBody.get("status").asInt()).isEqualTo(unknownBody.get("status").asInt());
        assertThat(againBody.get("code").asText()).isEqualTo(unknownBody.get("code").asText());
        assertThat(againBody.get("title").asText()).isEqualTo(unknownBody.get("title").asText());

        // An expired link reads the same way.
        String expired = linkToken(mcpRaw(token, call("request_form_upload", "")));
        try (Connection c = ownerConnection(DB);
                PreparedStatement st = c.prepareStatement(
                        "UPDATE mcp_form_upload SET expires_at = now() - interval '1 minute',"
                                + " created_at = now() - interval '20 minutes' WHERE token_sha256 = ?")) {
            st.setString(1, sha(expired));
            assertThat(st.executeUpdate()).isEqualTo(1);
        }
        assertThat(postUpload(URI.create(url("/api/v1/mcp-uploads/" + expired)),
                "a.docx", minimalDocx()).statusCode()).isEqualTo(404);
    }

    @Test
    void aTypeWeDoNotServeFailsTheUploadButKeepsTheStory() throws Exception {
        Browser browser = signIn("subject-mcp-upload-bad", ISSUER);
        String token = issueToken(browser, "u3", "[\"fill\",\"read\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        JsonNode asked = readJson(mcpRaw(token, call("request_form_upload", "")))
                .get("result").get("structuredContent");
        long uploadId = asked.get("upload_id").asLong();
        String uploadToken = asked.get("upload_url").asText()
                .substring(asked.get("upload_url").asText().lastIndexOf('/') + 1);

        HttpResponse<String> posted = postUpload(URI.create(url("/api/v1/mcp-uploads/" + uploadToken)),
                "evil.exe", "not a document".getBytes());
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(readJson(posted).get("state").asText()).isEqualTo("FAILED");

        JsonNode state = readJson(mcpRaw(token, call("get_form_upload",
                "\"upload_id\":" + uploadId))).get("result").get("structuredContent");
        assertThat(state.get("state").asText()).isEqualTo("FAILED");
        assertThat(state.get("reason").asText()).isNotBlank();
    }

    @Test
    void anotherWorkspacesUploadIdAnswersAsIfItDidNotExist() throws Exception {
        Browser first = signIn("subject-mcp-upload-ws1", ISSUER);
        String token1 = issueToken(first, "w1", "[\"fill\",\"read\"]").token();
        assertThat(mcpInitialize(token1).statusCode()).isEqualTo(200);
        long uploadId = readJson(mcpRaw(token1, call("request_form_upload", "")))
                .get("result").get("structuredContent").get("upload_id").asLong();

        Browser second = signIn("subject-mcp-upload-ws2", ISSUER + "-b");
        String token2 = issueToken(second, "w2", "[\"read\"]").token();
        assertThat(mcpInitialize(token2).statusCode()).isEqualTo(200);
        JsonNode answer = readJson(mcpRaw(token2, call("get_form_upload",
                "\"upload_id\":" + uploadId)));
        // Same answer the id's absence would get: a refusal, not the row.
        assertThat(answer.get("result").get("isError").asBoolean()).isTrue();
        assertThat(answer.get("result").get("structuredContent").get("refused").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void aTokenNeverLandsInStorage() throws Exception {
        Browser browser = signIn("subject-mcp-upload-secret", ISSUER);
        String token = issueToken(browser, "u4", "[\"fill\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        String uploadToken = linkToken(mcpRaw(token, call("request_form_upload", "")));

        try (Connection c = ownerConnection(DB);
                PreparedStatement st = c.prepareStatement(
                        "SELECT token_sha256 FROM mcp_form_upload WHERE token_sha256 = ?")) {
            st.setString(1, sha(uploadToken));
            ResultSet rs = st.executeQuery();
            assertThat(rs.next()).isTrue();
            // The digest alone: 64 lowercase hex, never the token itself.
            assertThat(rs.getString(1)).matches("[0-9a-f]{64}");
            assertThat(rs.next()).isFalse();
        }
        try (Connection c = ownerConnection(DB);
                PreparedStatement st = c.prepareStatement(
                        "SELECT count(*) FROM mcp_form_upload WHERE token_sha256 = ?")) {
            st.setString(1, uploadToken);
            ResultSet rs = st.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    private HttpResponse<String> mcpInitialize(String token) throws Exception {
        return mcpRaw(token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
    }

    private String linkToken(HttpResponse<String> response) throws Exception {
        String link = readJson(response).get("result").get("structuredContent").get("upload_url").asText();
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private static String sha(String raw) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{" + arguments + "}}}";
    }

    @Test
    void aFormLargerThanThePlainBodyLimitStillFitsTheUploadLimit() throws Exception {
        Browser browser = signIn("subject-mcp-upload-size", ISSUER);
        String token = issueToken(browser, "sz", "[\"fill\",\"read\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        String uploadToken = linkToken(mcpRaw(token, call("request_form_upload", "")));

        // A Word file a person really uploads is bigger than the plain-body cap: it is the
        // intake's own upload limit, not the API's, that the file answers to.
        byte[] padding = new byte[2 * 1024 * 1024];
        new java.util.Random(42).nextBytes(padding);
        byte[] docx = io.github.vihuynh72.brownie.api.document.docx.RawDocx.builder()
                .document("<w:p><w:r><w:t xml:space=\"preserve\">Name: </w:t></w:r>"
                        + "<w:r><w:t>____________</w:t></w:r></w:p>")
                .part("word/media/pad.bin", "application/octet-stream", padding)
                .build();

        HttpResponse<String> posted = postUpload(URI.create(url("/api/v1/mcp-uploads/" + uploadToken)),
                "registration.docx", docx);
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(readJson(posted).get("state").asText()).isEqualTo("READY");
    }

    @Test
    void aFileOverTheUploadLimitEndsWithAReasonNotAHang() throws Exception {
        Browser browser = signIn("subject-mcp-upload-huge", ISSUER);
        String token = issueToken(browser, "huge", "[\"fill\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        String uploadToken = linkToken(mcpRaw(token, call("request_form_upload", "")));

        byte[] overLimit = new byte[11 * 1024 * 1024];
        new java.util.Random(7).nextBytes(overLimit);
        HttpResponse<String> posted = postUpload(URI.create(url("/api/v1/mcp-uploads/" + uploadToken)),
                "huge.docx", overLimit);
        assertThat(posted.statusCode()).isEqualTo(200);
        JsonNode body = readJson(posted);
        assertThat(body.get("state").asText()).isEqualTo("FAILED");
        assertThat(body.get("reason").asText()).isNotBlank();
    }

    @Test
    void aWordProcessorFileOtherThanDocxStillOpensAsATemplate() throws Exception {
        Browser browser = signIn("subject-mcp-upload-odt", ISSUER);
        String token = issueToken(browser, "odt", "[\"fill\",\"read\"]").token();
        assertThat(mcpInitialize(token).statusCode()).isEqualTo(200);
        String uploadToken = linkToken(mcpRaw(token, call("request_form_upload", "")));

        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("fixtures/public/forms"))) {
            current = current.getParent();
        }
        byte[] odt = Files.readAllBytes(current.resolve("fixtures/public/forms/membership-application.odt"));

        HttpResponse<String> posted = postUpload(URI.create(url("/api/v1/mcp-uploads/" + uploadToken)),
                "membership-application.odt", odt);
        assertThat(posted.statusCode()).isEqualTo(200);
        assertThat(readJson(posted).get("state").asText()).isEqualTo("READY");
    }

    private static byte[] minimalDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String label : new String[] {"Name", "Email", "Phone", "Date of birth", "Address",
                    "Availability", "Emergency contact", "Signature date"}) {
                doc.createParagraph().createRun().setText(label + ": ______________________________");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] acroformPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm form =
                    new org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm(pdf);
            pdf.getDocumentCatalog().setAcroForm(form);
            for (String name : new String[] {"name", "email", "phone", "date_of_birth", "address",
                    "availability", "emergency_contact", "signature_date"}) {
                org.apache.pdfbox.pdmodel.interactive.form.PDTextField field =
                        new org.apache.pdfbox.pdmodel.interactive.form.PDTextField(form);
                field.setPartialName(name);
                form.getFields().add(field);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private HttpResponse<String> postUpload(URI uri, String filename, byte[] body) throws Exception {
        String target = uri + "?filename=" + java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8);
        return http.send(HttpRequest.newBuilder(URI.create(target))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
