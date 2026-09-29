package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.TreeMap;

/**
 * The one way this package talks to Google: every call has a connection and a
 * read deadline, never follows a redirect (an answer that moves elsewhere is
 * not Google's answer), and reads at most a stated number of bytes, so a slow,
 * wandering or enormous answer cannot hold a request thread or fill the heap.
 * The status and the bytes come back as they are; what they mean is for the
 * caller to decide. Nothing here logs a request or an answer.
 *
 * <p>A change in a person's account goes only through {@link #write}, which
 * sends only the requests {@link GoogleWrite} lists, and which tells a
 * request that never left apart from one whose answer was lost.
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

    /**
     * What became of a write. An answer came back; or the request certainly
     * never left (no connection could be made, so Google cannot have seen
     * it); or it may have left and whatever Google answered was lost. The
     * last is never read as "not done": a change Google received may have
     * happened whether or not its answer arrived.
     */
    sealed interface WriteExchange permits Answered, NotSent, Lost {
    }

    record Answered(Answer answer) implements WriteExchange {
    }

    record NotSent() implements WriteExchange {
    }

    record Lost() implements WriteExchange {
    }

    /**
     * Sends one of the writes {@link GoogleWrite} lists, to the address it
     * names (with {@code pathValue} as its one identifier, if it has one) and
     * with only its own query parameters. The body and its type are the
     * caller's; everything else about the request is fixed here.
     */
    WriteExchange write(
            GoogleClientSettings settings, GoogleWrite write, String pathValue, String accessToken,
            MediaType contentType, byte[] body, int maxAnswerBytes) {
        URI base = write.api() == GoogleWrite.Api.GOOGLE_DOCS ? settings.docsApiBaseUri() : settings.apiBaseUri();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(base).path(write.pathTemplate());
        new TreeMap<>(write.query()).forEach(builder::queryParam);
        URI uri = pathValue == null ? builder.encode().build().toUri() : builder.encode().buildAndExpand(pathValue).toUri();
        try {
            return new Answered(restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(contentType)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((ignoredRequest, response) -> new Answer(
                            response.getStatusCode().value(),
                            response.getHeaders().getContentType(),
                            readAtMost(response.getBody(), maxAnswerBytes))));
        } catch (AnswerTooLargeException e) {
            return new Lost();
        } catch (RestClientException | UncheckedIOException e) {
            return neverLeft(e) ? new NotSent() : new Lost();
        }
    }

    /** No connection could be made at all, so nothing reached Google. Anything else (a timeout, a reset) may have come after the request left. */
    private static boolean neverLeft(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** The answer as JSON; an answer that is not is Google failing, not a verdict on anything. */
    JsonNode json(Answer answer) {
        try {
            return objectMapper.readTree(answer.body());
        } catch (JacksonException e) {
            throw new ProviderUnavailableException("Google answered with something that is not JSON.");
        }
    }

    /** A value written as JSON, for a request body. */
    byte[] jsonBytes(Object value) {
        return objectMapper.writeValueAsBytes(value);
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
