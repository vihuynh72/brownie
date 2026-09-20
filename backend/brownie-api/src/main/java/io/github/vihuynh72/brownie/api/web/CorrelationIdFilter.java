package io.github.vihuynh72.brownie.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns every request a correlation ID -- the caller's own, if it sent
 * one, otherwise a freshly generated one -- before anything else runs. The
 * order is stated and not left to chance: a filter with none runs after
 * the session lookup, sign-in and the rate limiter, each of which can
 * answer a request by itself, and what they answered and logged would
 * carry no ID that the two could be matched by.
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // The caller's own only if it is plainly an identifier. It goes into every log line and comes back in a
        // header and in error bodies, some written by hand; and all of those have to show the same value, which
        // they would not if each place decided for itself whether the caller's was fit to repeat.
        String incoming = request.getHeader(HEADER_NAME);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming.trim()).matches()
                ? incoming.trim()
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
