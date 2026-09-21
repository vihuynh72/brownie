package io.github.vihuynh72.brownie.storage.azure;

import com.azure.storage.blob.BlobServiceClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Answers whether the blob store is actually reachable, so that "this process
 * is healthy" cannot stay true through an outage of the one place every
 * uploaded file and every generated document lives.
 *
 * <p>It asks the cheapest question that requires a real round trip and real
 * authorization: does this container exist. The answer does not matter -- a
 * container that has not been created yet is made on first write -- only that
 * the store answered at all. A store that is away, or an identity that has
 * lost its permission, both fail here, which is what an operator needs to
 * know before people start seeing failed uploads.
 *
 * <p>There is no timeout of its own: the client is already bounded to two
 * tries, and whatever is polling this endpoint has a timeout, so a store that
 * hangs shows up as a probe that does not answer, which means the same thing.
 */
class BlobStoreHealthIndicator implements HealthIndicator {

    private final BlobServiceClient client;
    private final String containerName;

    BlobStoreHealthIndicator(BlobServiceClient client, String containerName) {
        this.client = client;
        this.containerName = containerName;
    }

    @Override
    public Health health() {
        try {
            client.getBlobContainerClient(containerName).exists();
            return Health.up().build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("reason", e.getClass().getSimpleName()).build();
        }
    }
}
