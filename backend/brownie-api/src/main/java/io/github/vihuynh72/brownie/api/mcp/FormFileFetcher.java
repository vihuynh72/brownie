package io.github.vihuynh72.brownie.api.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches the file a chat app attached for {@code add_form_file}. Only
 * {@code https} on an {@code *.oaiusercontent.com} host is ever asked for,
 * redirects are never followed at all (a redirect is a new URL, and a new
 * URL is a new question), and the read is bounded by the same size cap an
 * upload route takes plus connect and read timeouts. Injected so tests can
 * stub the network out entirely.
 */
interface FormFileFetcher {

    byte[] fetch(URI uri) throws IOException, InterruptedException;
}

final class OaiFormFileFetcher implements FormFileFetcher {

    /** Same ceiling the person-facing upload takes: the intake enforces its own limit too. */
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;

    OaiFormFileFetcher() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // Never: a redirect is a different URL, and a different URL
                // gets re-asked through the allowlist, not chased silently.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public byte[] fetch(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(READ_TIMEOUT).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new FormFileFetchRefusedException("HTTP " + response.statusCode());
        }
        long length = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (length > MAX_BYTES) {
            throw new FormFileFetchRefusedException("The file is bigger than " + (MAX_BYTES / 1024 / 1024) + " MB.");
        }
        try (InputStream body = response.body()) {
            // Read bounded: content-length can lie, the bytes cannot.
            ByteLimit streams = new ByteLimit(body);
            return streams.readAll(MAX_BYTES);
        }
    }

    static final class FormFileFetchRefusedException extends IOException {
        FormFileFetchRefusedException(String message) {
            super(message);
        }
    }

    private static final class ByteLimit {
        private final InputStream in;
        private ByteLimit(InputStream in) { this.in = in; }
        byte[] readAll(long cap) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > cap) {
                    throw new FormFileFetchRefusedException("The file is bigger than " + (cap / 1024 / 1024) + " MB.");
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }
}
