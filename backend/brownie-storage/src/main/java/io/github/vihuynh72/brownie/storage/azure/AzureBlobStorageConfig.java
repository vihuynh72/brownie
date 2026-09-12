package io.github.vihuynh72.brownie.storage.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Supplies the local/test Azure Blob adapter to a Brownie runtime. */
@Configuration
public class AzureBlobStorageConfig {

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

    @Bean
    BlobStore blobStore(BlobServiceClient blobServiceClient) {
        return new AzureBlobStore(blobServiceClient);
    }
}
