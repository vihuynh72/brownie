package io.github.vihuynh72.brownie.storage.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Supplies the local/test Azure Blob adapter to a Brownie runtime. */
@Configuration
public class AzureBlobStorageConfig {

    /**
     * The client's own default is four tries with pauses that grow to many
     * seconds, which held a person's request open for about 45 seconds when
     * the store was down. Two tries a second apart are enough to ride out a
     * dropped connection; past that the caller is told the store is away
     * and decides for itself, which the worker's sweeps already do by
     * trying again on their next pass. Each try still gets a minute, enough
     * for the largest upload the application accepts.
     */
    private static final RequestRetryOptions BOUNDED_RETRIES =
            new RequestRetryOptions(RetryPolicyType.FIXED, 2, 60, 1_000L, 1_000L, null);

    @Bean
    BlobServiceClient blobServiceClient(
            Environment environment, @Value("${brownie.storage.local-connection:}") String localConnection) {
        boolean localConnectionSet = localConnection != null && !localConnection.isBlank();
        if (environment.matchesProfiles("pilot", "production")) {
            if (localConnectionSet) {
                throw new IllegalStateException(
                        "BROWNIE_LOCAL_STORAGE_CONNECTION must not be set outside the local and test profiles.");
            }
            throw new IllegalStateException(
                    "No production Blob Storage adapter exists yet; only BROWNIE_LOCAL_STORAGE_CONNECTION"
                            + " (local/test only) is supported today.");
        }
        if (!localConnectionSet) {
            throw new IllegalStateException("BROWNIE_LOCAL_STORAGE_CONNECTION is required on this profile.");
        }
        return clientFor(localConnection);
    }

    static BlobServiceClient clientFor(String connectionString) {
        return new BlobServiceClientBuilder().connectionString(connectionString).retryOptions(BOUNDED_RETRIES).buildClient();
    }

    @Bean
    BlobStore blobStore(BlobServiceClient blobServiceClient) {
        return new AzureBlobStore(blobServiceClient);
    }

    @Bean
    DeletionLedgerArchive deletionLedgerArchive(BlobServiceClient blobServiceClient) {
        return new AzureDeletionLedgerArchive(blobServiceClient);
    }
}
