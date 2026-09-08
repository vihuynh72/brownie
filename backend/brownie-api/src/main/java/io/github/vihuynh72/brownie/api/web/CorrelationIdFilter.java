package io.github.vihuynh72.brownie.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every request a correlation ID -- the caller's own, if it sent
 * one, otherwise a freshly generated one -- before anything else runs.
 *
 * <p>The ID is placed in the logging {@link MDC} for the lifetime of the
 * request, so every structured log line written while handling it carries
 * the same value with no per-log-statement effort, echoed back as a
 * response header so a caller can quote it when asking for help, and read
 * back out of the MDC by {@link ApiExceptionHandler} to appear in the
 * error body itself. Removed from the MDC in a {@code finally} block:
 * Tomcat reuses request-handling threads, and a value left behind would
 * leak into an unrelated later request on the same thread.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String correlationId =
                (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming.trim();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
