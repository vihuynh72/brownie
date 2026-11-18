package io.github.vihuynh72.brownie.api.document.render;

import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** The private broker owns Railway credentials; a job VM receives only the document. */
final class RailwayDocumentSandbox {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final URI endpoint;
    private final String token;
    private final String expectedImageId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    static RailwayDocumentSandbox fromEnvironment(String expectedImageId) {
        String endpoint = System.getenv("BROWNIE_RENDER_SERVICE_URL");
        String token = System.getenv("BROWNIE_RENDER_SERVICE_TOKEN");
        if (endpoint == null && token == null) return null;
        return new RailwayDocumentSandbox(endpoint, token, expectedImageId);
    }

    RailwayDocumentSandbox(String endpoint, String token, String expectedImageId) {
        if (endpoint == null || token == null || token.length() < 32 || expectedImageId == null
                || !expectedImageId.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Remote rendering needs its private endpoint, service token and immutable image ID.");
        }
        this.endpoint = URI.create(endpoint);
        String host = this.endpoint.getHost();
        boolean privateHttp = "http".equals(this.endpoint.getScheme()) && host != null
                && (host.endsWith(".railway.internal") || host.equals("127.0.0.1") || host.equals("localhost"));
        if ((!"https".equals(this.endpoint.getScheme()) && !privateHttp) || host == null
                || this.endpoint.getUserInfo() != null || this.endpoint.getQuery() != null
                || this.endpoint.getFragment() != null || !"/render".equals(this.endpoint.getPath())) {
            throw new IllegalArgumentException("The renderer requires HTTPS or Railway private HTTP at /render.");
        }
        this.token = token;
        this.expectedImageId = expectedImageId;
    }

    private record Request(byte[] input, String inputName, String inputFilter, String convertTo, String outputName,
                           byte[] outputSignature, long outputMaxBytes, long deadlineSeconds, String expectedImageId) {}
    private record Answer(byte[] bytes, String imageId, List<String> strayEntries, String step, Integer exitStatus, Long quota) {}

    IsolatedLibreOffice.Output run(IsolatedLibreOffice.Job job) {
        var payload = new Request(job.input(), job.inputName(), job.inputFilter(), job.convertTo(), job.outputName(),
                job.outputSignature(), job.outputMaxBytes(), Math.max(1, job.deadline().toSeconds()), expectedImageId);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload))).build();
        long maxResponseBytes = Math.min(30L * 1024 * 1024, ((job.outputMaxBytes() + 2) / 3) * 4 + 16_384);
        var future = http.sendAsync(request, response -> new LimitedBody(maxResponseBytes));
        try {
            var response = future.get(125, TimeUnit.SECONDS);
            Answer answer = JSON.readValue(response.body(), Answer.class);
            if (response.statusCode() != 200) {
                if (answer.step() == null) throw IsolatedLibreOffice.JobFailure.of(IsolatedLibreOffice.JobFailure.Step.START_FAILED);
                var step = IsolatedLibreOffice.JobFailure.Step.valueOf(answer.step());
                if (step == IsolatedLibreOffice.JobFailure.Step.EXITED) throw IsolatedLibreOffice.JobFailure.exited(answer.exitStatus() == null ? -1 : answer.exitStatus());
                if (step == IsolatedLibreOffice.JobFailure.Step.OVER_QUOTA) throw IsolatedLibreOffice.JobFailure.overQuota(job.outputMaxBytes());
                throw IsolatedLibreOffice.JobFailure.of(step);
            }
            if (!expectedImageId.equals(answer.imageId())) {
                throw IsolatedLibreOffice.JobFailure.of(IsolatedLibreOffice.JobFailure.Step.IMAGE_NOT_APPROVED);
            }
            if (answer.bytes() == null || answer.bytes().length >= job.outputMaxBytes()) {
                throw IsolatedLibreOffice.JobFailure.overQuota(job.outputMaxBytes());
            }
            byte[] bytes = answer.bytes();
            if (bytes.length < job.outputSignature().length ||
                    !Arrays.equals(bytes, 0, job.outputSignature().length, job.outputSignature(), 0, job.outputSignature().length)) {
                throw IsolatedLibreOffice.JobFailure.of(IsolatedLibreOffice.JobFailure.Step.WRONG_SIGNATURE);
            }
            if (answer.strayEntries() == null || answer.strayEntries().size() > 16 ||
                    answer.strayEntries().stream().anyMatch(Objects::isNull)) {
                throw IsolatedLibreOffice.JobFailure.of(IsolatedLibreOffice.JobFailure.Step.STAGING);
            }
            return new IsolatedLibreOffice.Output(bytes, answer.imageId(), List.copyOf(answer.strayEntries()));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw IsolatedLibreOffice.JobFailure.of(IsolatedLibreOffice.JobFailure.Step.INTERRUPTED, e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw IsolatedLibreOffice.JobFailure.of(IsolatedLibreOffice.JobFailure.Step.TIMED_OUT, e);
        } catch (IsolatedLibreOffice.JobFailure e) {
            throw e;
        } catch (ExecutionException | RuntimeException e) {
            throw IsolatedLibreOffice.JobFailure.of(IsolatedLibreOffice.JobFailure.Step.START_FAILED, e);
        }
    }

    /** Refuse a large response while receiving it, before JSON/base64 allocation. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long maxBytes;
        private long received;
        private Flow.Subscription subscription;
        private boolean failed;
        private LimitedBody(long maxBytes) { this.maxBytes = maxBytes; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > maxBytes) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("Renderer response exceeds its output quota."));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
}
