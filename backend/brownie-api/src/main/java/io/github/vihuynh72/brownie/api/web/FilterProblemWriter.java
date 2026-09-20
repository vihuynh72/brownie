package io.github.vihuynh72.brownie.api.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Writes an error body from a servlet filter in the same shape {@link
 * ApiExceptionHandler} gives every error a controller raises. A filter
 * answers before Spring MVC is involved, so it has no message converter to
 * hand an object to; the body is built here instead, from text this
 * application wrote and one value that came from outside (the correlation
 * id), which is used only if it could not possibly break out of a JSON
 * string.
 */
public final class FilterProblemWriter {

    private FilterProblemWriter() {
    }

    /** {@code title}, {@code detail} and {@code code} are this application's own words and must contain no quote or backslash. */
    public static void write(
            HttpServletRequest request, HttpServletResponse response, int status, String title, String detail, String code)
            throws IOException {
        String correlationId = correlationIdOf(request, response);
        response.setStatus(status);
        response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\",\"correlationId\":\"" + correlationId
                + "\",\"fields\":[],\"recoveryActions\":[]}";
        try {
            response.getWriter().write(body);
        } catch (IllegalStateException alreadyWritingBytes) {
            // Whatever was answering before this had already asked for the byte stream (a response may be written
            // through one or the other, never both). What it wrote is gone; this goes out the way it chose.
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The id this request already has if it has one, the caller's if it is safe to repeat, otherwise a new one. */
    static String correlationIdOf(HttpServletRequest request, HttpServletResponse response) {
        for (String candidate : new String[] {
                MDC.get(CorrelationIdFilter.MDC_KEY),
                response.getHeader(CorrelationIdFilter.HEADER_NAME),
                request.getHeader(CorrelationIdFilter.HEADER_NAME)}) {
            if (candidate != null && CorrelationIdFilter.SAFE_ID.matcher(candidate.trim()).matches()) {
                return candidate.trim();
            }
        }
        return UUID.randomUUID().toString();
    }
}
