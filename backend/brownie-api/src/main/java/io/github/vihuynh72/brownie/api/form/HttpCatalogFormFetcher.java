package io.github.vihuynh72.brownie.api.form;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final Duration timeout;

    public HttpCatalogFormFetcher(Set<String> allowedHosts, boolean requireTls) {
        this(allowedHosts, requireTls, TIMEOUT);
    }

    HttpCatalogFormFetcher(Set<String> allowedHosts, boolean requireTls, Duration timeout) {
        this.allowedHosts = allowedHosts;
        this.requireTls = requireTls;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public byte[] fetch(FormLibraryCatalog.Entry entry) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        URI uri = URI.create(entry.url());
        for (int redirects = 0; ; redirects++) {
            requireAllowed(uri);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Fetching " + entry.id() + " timed out");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofNanos(remaining))
                    .header("User-Agent", "Brownie/1.0 (+https://brownie.example) form-library")
                    .GET().build();
            var future = client.sendAsync(request, info -> {
                if (info.statusCode() != 200) return new CappedBody(true, false);
                return new CappedBody(false, info.headers().firstValueAsLong("Content-Length").orElse(-1) > MAX_BYTES);
            });
            HttpResponse<byte[]> response;
            try {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                response = future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("Fetching " + entry.id() + " timed out", e);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("Fetching " + entry.id() + " was interrupted", e);
            } catch (ExecutionException e) {
                throw new IOException("Fetching " + entry.id() + " failed: " + e.getCause().getMessage(), e.getCause());
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                if (redirects >= MAX_REDIRECTS) throw new IOException("Fetching " + entry.id() + " kept redirecting");
                String location = response.headers().firstValue("Location").orElseThrow(
                        () -> new IOException("Fetching " + entry.id() + " answered a redirect with no destination"));
                uri = uri.resolve(location);
                continue;
            }
            if (status != 200) throw new IOException("Fetching " + entry.id() + " answered " + status);
            return response.body();
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

    /** Cancels refused bodies immediately and bounds accepted bytes while they arrive. */
    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final boolean discard;
        private final boolean tooLarge;
        private Flow.Subscription subscription;

        CappedBody(boolean discard, boolean tooLarge) {
            this.discard = discard;
            this.tooLarge = tooLarge;
        }

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (discard || tooLarge) {
                subscription.cancel();
                if (tooLarge) result.completeExceptionally(new IOException("Form exceeds the byte cap"));
                else result.complete(new byte[0]);
            } else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Form exceeds the byte cap"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
