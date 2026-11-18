package io.github.vihuynh72.brownie.api.document.render;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class RailwayDocumentSandboxTest {
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final String TOKEN = "test-token-" + "x".repeat(32);
    private static final IsolatedLibreOffice.Job JOB = new IsolatedLibreOffice.Job(
            new byte[]{1, 2}, "input.docx", null, "pdf", "input.pdf", "%PDF-".getBytes(StandardCharsets.US_ASCII),
            1024, Duration.ofSeconds(60));

    @Test
    void authenticatesAndIndependentlyChecksTheReturnedImageSignatureQuotaAndStrayFiles() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        withAnswer(200, "{\"bytes\":\"" + encoded + "\",\"imageId\":\"" + IMAGE + "\",\"strayEntries\":[\"extra\"]}", remote -> {
            var output = remote.run(JOB);
            assertThat(output.bytes()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
            assertThat(output.strayEntries()).containsExactly("extra");
        });
        withAnswer(200, "{\"bytes\":\"" + encoded + "\",\"imageId\":\"wrong\",\"strayEntries\":[]}", remote ->
                assertThatThrownBy(() -> remote.run(JOB)).isInstanceOf(IsolatedLibreOffice.JobFailure.class).hasMessage("IMAGE_NOT_APPROVED"));
        withAnswer(200, "{\"bytes\":\"eA==\",\"imageId\":\"" + IMAGE + "\",\"strayEntries\":[]}", remote ->
                assertThatThrownBy(() -> remote.run(JOB)).isInstanceOf(IsolatedLibreOffice.JobFailure.class).hasMessage("WRONG_SIGNATURE"));
        withAnswer(502, "{\"step\":\"NO_OUTPUT\"}", remote ->
                assertThatThrownBy(() -> remote.run(JOB)).isInstanceOf(IsolatedLibreOffice.JobFailure.class).hasMessage("NO_OUTPUT"));
    }

    @Test
    void doesNotFollowRedirectsOrAcceptUnboundedResponses() throws Exception {
        withAnswer(302, "{}", remote -> assertThatThrownBy(() -> remote.run(JOB))
                .isInstanceOf(IsolatedLibreOffice.JobFailure.class).hasMessage("START_FAILED"));
        withAnswer(200, "x".repeat(20_000), remote -> assertThatThrownBy(() -> remote.run(JOB))
                .isInstanceOf(IsolatedLibreOffice.JobFailure.class).hasMessage("START_FAILED"));
    }

    @Test
    void refusesUnencryptedPublicEndpointsAndIncompleteConfiguration() {
        assertThatThrownBy(() -> new RailwayDocumentSandbox("http://public.example/render", TOKEN, IMAGE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RailwayDocumentSandbox("https://example.com/render", "", IMAGE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RailwayDocumentSandbox("https://example.com/render", TOKEN, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void withAnswer(int status, String body, java.util.function.Consumer<RailwayDocumentSandbox> check) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/render", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("\"expectedImageId\":\"" + IMAGE + "\"");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try { check.accept(new RailwayDocumentSandbox("http://127.0.0.1:" + server.getAddress().getPort() + "/render", TOKEN, IMAGE)); }
        finally { server.stop(0); }
    }
}
