package io.github.vihuynh72.brownie.storage.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Supplies the two blob clients a Brownie runtime uses: one for the files
 * people upload and everything derived from them, and one for the record of
 * what has been deleted.
 *
 * <p>They are two clients rather than one because the record has to survive
 * being restored over. If it lived in the same account as the files, a
 * restore of that account would roll the record back with everything else and
 * a deletion carried out since the backup would be undone without trace.
 * Hosted deployments therefore give each one a different account; locally
 * both address the same emulator, which is honest about what a development
 * machine is and keeps the local setup to one service.
 *
 * <p>How a client authenticates depends on where it runs, and the two are
 * deliberately exclusive. Locally it is a connection string with the
 * emulator's well-known key. Hosted, it is the endpoint plus whatever
 * identity the platform gives the process -- no key, no connection string,
 * nothing to leak into a configuration file or a log. A profile that sets
 * both, or neither, is refused at start-up rather than left to pick one.
 */
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

    /** Named so that the archive can ask for its own client rather than the one holding people's files. */
    public static final String DELETION_RECORD_CLIENT = "deletionRecordBlobServiceClient";

    @Bean
    BlobServiceClient blobServiceClient(
            Environment environment,
            @Value("${brownie.storage.local-connection:}") String localConnection,
            @Value("${brownie.storage.endpoint:}") String endpoint) {
        return clientFor(environment, localConnection, endpoint, "brownie.storage.endpoint");
    }

    @Bean(DELETION_RECORD_CLIENT)
    BlobServiceClient deletionRecordBlobServiceClient(
            Environment environment,
            @Value("${brownie.storage.local-connection:}") String localConnection,
            @Value("${brownie.storage.deletion-record-endpoint:}") String deletionRecordEndpoint,
            @Value("${brownie.storage.endpoint:}") String endpoint) {
        if (hosted(environment) && deletionRecordEndpoint.equals(endpoint) && !endpoint.isBlank()) {
            throw new IllegalStateException(
                    "brownie.storage.deletion-record-endpoint must be a different storage account from"
                            + " brownie.storage.endpoint: a restore of the files would otherwise roll back the"
                            + " record of what was deleted.");
        }
        return clientFor(environment, localConnection, deletionRecordEndpoint, "brownie.storage.deletion-record-endpoint");
    }

    private BlobServiceClient clientFor(
            Environment environment, String localConnection, String endpoint, String endpointSetting) {
        boolean localConnectionSet = localConnection != null && !localConnection.isBlank();
        boolean endpointSet = endpoint != null && !endpoint.isBlank();
        if (hosted(environment)) {
            if (localConnectionSet) {
                throw new IllegalStateException(
                        "BROWNIE_LOCAL_STORAGE_CONNECTION must not be set outside the local and test profiles.");
            }
            if (!endpointSet) {
                throw new IllegalStateException(endpointSetting + " is required on this profile.");
            }
            return clientFor(endpoint, managedIdentity());
        }
        if (endpointSet) {
            throw new IllegalStateException(
                    endpointSetting
                            + " is set on a local or test profile, where storage is reached with"
                            + " BROWNIE_LOCAL_STORAGE_CONNECTION instead. Set one or the other, not both.");
        }
        if (!localConnectionSet) {
            throw new IllegalStateException("BROWNIE_LOCAL_STORAGE_CONNECTION is required on this profile.");
        }
        return clientFor(localConnection);
    }

    private static boolean hosted(Environment environment) {
        return environment.matchesProfiles("pilot", "production");
    }

    /**
     * Whatever identity the platform has given this process: on the deployed
     * machine, the one assigned to it, discovered over the instance metadata
     * service. Nothing is configured here, and no credential is stored
     * anywhere, which is the point -- there is nothing to rotate and nothing
     * to leak.
     */
    private static TokenCredential managedIdentity() {
        return new DefaultAzureCredentialBuilder().build();
    }

    static BlobServiceClient clientFor(String connectionString) {
        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .retryOptions(BOUNDED_RETRIES)
                .buildClient();
    }

    static BlobServiceClient clientFor(String endpoint, TokenCredential credential) {
        return new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .retryOptions(BOUNDED_RETRIES)
                .buildClient();
    }

    @Bean
    BlobStore blobStore(BlobServiceClient blobServiceClient) {
        return new AzureBlobStore(blobServiceClient);
    }

    /**
     * Two readings rather than one: the two stores are different accounts in
     * a hosted deployment, with different permissions, and either can be away
     * on its own. Naming them apart is what makes a probe's answer tell an
     * operator which.
     */
    @Bean
    HealthIndicator fileStorageHealthIndicator(BlobServiceClient blobServiceClient) {
        return new BlobStoreHealthIndicator(blobServiceClient, AzureBlobStore.CONTAINER_NAME);
    }

    @Bean
    HealthIndicator deletionRecordStorageHealthIndicator(
            @Qualifier(DELETION_RECORD_CLIENT) BlobServiceClient deletionRecordBlobServiceClient) {
        return new BlobStoreHealthIndicator(deletionRecordBlobServiceClient, AzureDeletionLedgerArchive.CONTAINER_NAME);
    }

    @Bean
    DeletionLedgerArchive deletionLedgerArchive(
            @Qualifier(DELETION_RECORD_CLIENT) BlobServiceClient deletionRecordBlobServiceClient) {
        return new AzureDeletionLedgerArchive(deletionRecordBlobServiceClient);
    }
}
