package io.github.vihuynh72.brownie.api.web;

import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdAndProblemWriterTest {

    /** What a person is told to quote has to be what the log holds, whoever wrote the answer. */
    @Test
    void anIdThatIsNotPlainlyAnIdentifierIsReplacedOnceAndEverywhereTheSame() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "req 42/a=\"");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> logged = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(request, response, (req, res) -> {
            logged.set(MDC.get(CorrelationIdFilter.MDC_KEY));
            FilterProblemWriter.write(request, response, 429, "Too Many Requests", "Wait.", "RATE_LIMITED");
        });

        assertThat(logged.get()).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(logged.get());
        assertThat(response.getContentAsString()).contains("\"correlationId\":\"" + logged.get() + "\"");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void aCallersOwnPlainIdentifierIsKept() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, " support-case-7:attempt.2 ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("support-case-7:attempt.2");
    }

    /** A response is written through its writer or its byte stream, never both; whoever was answering first chose. */
    @Test
    void anAnswerIsStillWrittenWhenTheResponseWasAlreadyBeingWrittenAsBytes() throws Exception {
        MockHttpServletResponse underneath = new MockHttpServletResponse();
        HttpServletResponseWrapper bytesAlreadyChosen = new HttpServletResponseWrapper(underneath) {
            @Override
            public PrintWriter getWriter() {
                throw new IllegalStateException("getOutputStream() has already been called for this response");
            }
        };

        FilterProblemWriter.write(
                new MockHttpServletRequest("GET", "/api/v1/me"), bytesAlreadyChosen, 503, "Service Unavailable", "Try again.",
                "DATABASE_UNAVAILABLE");

        assertThat(underneath.getStatus()).isEqualTo(503);
        assertThat(underneath.getContentAsString()).contains("\"code\":\"DATABASE_UNAVAILABLE\"");
    }
}
