package io.github.vihuynh72.brownie.api.form;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Downloads a catalog form under the same rules the catalog was verified
 * with: only a host on the allowlist (a listed domain or anything under
 * it), https only, at most two redirects with the host re-checked after
 * each, a 10 MiB cap and a 20 second ceiling on the whole trip.
 */
public final class HttpCatalogFormFetcher implements CatalogFormFetcher {

    /** The largest form the intake reads anyway; the cap keeps a surprised host from sending more. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 2;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final Set<String> allowedHosts;
    private final boolean requireTls;
    private final HttpClient client;

    public HttpCatalogFormFetcher(Set<String> allowedHosts, boolean requireTls) {
        this.allowedHosts = allowedHosts;
        this.requireTls = requireTls;
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public byte[] fetch(FormLibraryCatalog.Entry entry) throws IOException {
        // One wall-clock deadline for the whole trip -- the headers, the
        // redirects and the body -- since a request timeout only reaches
        // the response's first bytes.
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        URI uri = URI.create(entry.url());
        for (int redirects = 0; ; redirects++) {
            requireAllowed(uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Brownie/1.0 (+https://brownie.example) form-library")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Fetching " + entry.id() + " was interrupted", e);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                if (redirects >= MAX_REDIRECTS) {
                    throw new IOException("Fetching " + entry.id() + " kept redirecting");
                }
                String location = response.headers().firstValue("Location").orElseThrow(
                        () -> new IOException("Fetching " + entry.id() + " answered a redirect with no destination"));
                uri = uri.resolve(location);
                continue;
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new IOException("Fetching " + entry.id() + " answered " + status);
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > MAX_BYTES) {
                closeQuietly(response.body());
                throw new IOException("Fetching " + entry.id() + " offered " + declared + " bytes, over the cap");
            }
            return readCapped(response.body(), entry.id(), deadline);
        }
    }

    private void requireAllowed(URI uri) throws IOException {
        if (requireTls && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Form URLs must be https");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean allowed = allowedHosts.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
        if (!allowed) {
            throw new IOException("Host " + host + " is not on the form-library allowlist");
        }
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // The redirect or refusal matters, not whether its body closed cleanly.
        }
    }

    private static byte[] readCapped(InputStream body, String id, long deadline) throws IOException {
        try (InputStream in = body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            for (int n; (n = in.read(chunk)) != -1; ) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("Fetching " + id + " ran out of its " + TIMEOUT.toSeconds() + " seconds mid-body");
                }
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("Fetching " + id + " sent more than the " + MAX_BYTES + "-byte cap");
                }
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }
}
