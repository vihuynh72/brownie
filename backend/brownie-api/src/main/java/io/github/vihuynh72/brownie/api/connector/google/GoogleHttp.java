package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The one way this package talks to Google: every call has a connection and a
 * read deadline, never follows a redirect (an answer that moves elsewhere is
 * not Google's answer), and reads at most a stated number of bytes, so a slow,
 * wandering or enormous answer cannot hold a request thread or fill the heap.
 * The status and the bytes come back as they are; what they mean is for the
 * caller to decide. Nothing here logs a request or an answer.
 */
public final class GoogleHttp {

    /** Google's answers for tokens, accounts and metadata are a few kilobytes; anything past this is not one of them. */
    static final int SMALL_ANSWER_BYTES = 64 * 1024;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    GoogleHttp(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public static GoogleHttp create(Duration connectTimeout, Duration readTimeout, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return new GoogleHttp(RestClient.builder().requestFactory(requestFactory).build(), objectMapper);
    }

    RestClient restClient() {
        return restClient;
    }

    /** An answer's status, declared type and bytes. */
    record Answer(int status, MediaType contentType, byte[] body) {

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        boolean isProviderFailure() {
            return status == 429 || status >= 500;
        }
    }

    /** The answer was larger than the caller said it could be. */
    static final class AnswerTooLargeException extends RuntimeException {
        AnswerTooLargeException() {
            super("Google's answer was larger than allowed.");
        }
    }

    /**
     * Sends the request and reads its answer, up to {@code maxBytes}. A request
     * that never got an answer (refused, reset, timed out) is {@link
     * ProviderUnavailableException}; an answer larger than {@code maxBytes} is
     * {@link AnswerTooLargeException} and nothing past the limit is read.
     */
    Answer send(RestClient.RequestHeadersSpec<?> request, int maxBytes) {
        try {
            return request.exchange((ignoredRequest, response) -> new Answer(
                    response.getStatusCode().value(),
                    response.getHeaders().getContentType(),
                    readAtMost(response.getBody(), maxBytes)));
        } catch (RestClientException | UncheckedIOException e) {
            // Only the kind of failure: its message can carry the address, and the address can carry an identifier.
            throw new ProviderUnavailableException("Google could not be reached (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** The answer as JSON; an answer that is not is Google failing, not a verdict on anything. */
    JsonNode json(Answer answer) {
        try {
            return objectMapper.readTree(answer.body());
        } catch (JacksonException e) {
            throw new ProviderUnavailableException("Google answered with something that is not JSON.");
        }
    }

    /** A string field, or null when it is absent or not a string. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.stringValue() : null;
    }

    private static byte[] readAtMost(InputStream body, int maxBytes) {
        try (InputStream in = body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new AnswerTooLargeException();
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
