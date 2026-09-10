package io.github.vihuynh72.brownie.api.storage.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Builds the one {@link BlobServiceClient} this application uses.
 * Constructing a client here makes no network call of its own -- the
 * Azure SDK only reaches the network when an operation (upload, exists,
 * delete...) actually runs -- so this bean is safe to create even in the
 * fast, Docker-free test profile the rest of this application's context
 * tests already depend on; nothing here requires Azurite to be running.
 *
 * <p>Only a local, connection-string-based Azurite target exists today. A
 * production Azure Blob Storage adapter (managed identity, Key Vault) is
 * unresolved, the same way this codebase already leaves production
 * database credential rotation unresolved elsewhere.
 */
@Configuration
class BlobStorageConfig {

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
        return new BlobServiceClientBuilder().connectionString(localConnection).buildClient();
    }
}
