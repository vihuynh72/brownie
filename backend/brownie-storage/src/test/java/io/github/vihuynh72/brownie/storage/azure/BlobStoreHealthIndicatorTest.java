package io.github.vihuynh72.brownie.storage.azure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reading has to be down when the store cannot be reached. Proven against
 * an address nothing is listening on, the same way the adapter's own
 * unreachable-store behaviour is proven: no emulator, no container, and no
 * chance of passing because something else happened to be running.
 */
class BlobStoreHealthIndicatorTest {

    // Azurite's published development account; only the endpoint matters here.
    private static final String NOBODY_HOME = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
            + "BlobEndpoint=http://127.0.0.1:1/devstoreaccount1;";

    @Test
    void reportsDownWhenTheStoreCannotBeReached() {
        BlobStoreHealthIndicator indicator =
                new BlobStoreHealthIndicator(AzureBlobStorageConfig.clientFor(NOBODY_HOME), "artifacts");

        assertEquals(Status.DOWN, indicator.health().getStatus());
    }
}
