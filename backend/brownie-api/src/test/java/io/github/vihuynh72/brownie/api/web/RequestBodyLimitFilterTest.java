package io.github.vihuynh72.brownie.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(2048);

    @Test
    void aJsonBodyThatDeclaresMoreThanTheLimitIsRefusedBeforeItIsRead() throws Exception {
        MockHttpServletRequest request = json(new byte[4096]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(new ObjectMapper().readTree(response.getContentAsString()).get("code").asText()).isEqualTo("CONTENT_TOO_LARGE");
    }

    @Test
    void aBodyThatDoesNotSayHowLongItIsIsCutOffAsItIsReadNotHeldWhole() throws Exception {
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/v1/workspaces/1/documents") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        chunked.setContentType("application/json");
        chunked.setContent(new byte[4096]);
        FilterChain readsEverything = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(chunked, new MockHttpServletResponse(), readsEverything))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void anOrdinaryJsonBodyAndAFileUploadAreLeftAlone() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(json("{\"title\":\"Minutes\"}".getBytes(StandardCharsets.UTF_8)), new MockHttpServletResponse(), (req, res) -> {
            assertThat(req.getInputStream().readAllBytes()).hasSize(19);
            reached.set(true);
        });
        assertThat(reached).isTrue();

        // A file has its own, larger limit, enforced where the bytes are stored.
        MockHttpServletRequest upload = new MockHttpServletRequest("PUT", "/api/v1/workspaces/1/uploads/4/content");
        upload.setContentType("application/octet-stream");
        upload.setContent(new byte[1_000_000]);
        AtomicBoolean uploaded = new AtomicBoolean();
        filter.doFilter(upload, new MockHttpServletResponse(), (req, res) -> uploaded.set(true));
        assertThat(uploaded).isTrue();
    }

    @Test
    void whatTheBodyCallsItselfMakesNoDifference() throws Exception {
        for (String contentType : new String[] {"application/yaml", "text/plain", null}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/workspaces/1/documents/2/assist/interpret");
            request.setContentType(contentType);
            request.setContent(new byte[4096]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();

            filter.doFilter(request, response, (req, res) -> reached.set(true));

            assertThat(reached).as("content type %s", contentType).isFalse();
            assertThat(response.getStatus()).isEqualTo(413);
        }
    }

    @Test
    void onlyTheRouteThatReceivesAFileIsExemptNotAnythingThatLooksLikeIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/workspaces/1/uploads/4/content");
        request.setContentType("application/octet-stream");
        request.setContent(new byte[4096]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getStatus()).isEqualTo(413);
    }

    private static MockHttpServletRequest json(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/workspaces/1/documents");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
