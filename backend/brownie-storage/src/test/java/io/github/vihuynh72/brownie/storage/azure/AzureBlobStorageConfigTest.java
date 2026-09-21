package io.github.vihuynh72.brownie.storage.azure;

import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a client is built is decided by where it runs, and every way of getting
 * that wrong is refused at start-up rather than at the first upload.
 *
 * <p>The two ways that matter are opposite mistakes. A hosted deployment
 * carrying a connection string would be holding a key it should not have; a
 * development machine carrying an endpoint would be asking for an identity it
 * does not have, and would fail later, on a request, rather than now. Both
 * are stated here so that neither can be introduced quietly.
 */
class AzureBlobStorageConfigTest {

    private static final String LOCAL = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
            + "BlobEndpoint=http://127.0.0.1:1/devstoreaccount1;";
    private static final String FILES = "https://stfiles.blob.core.windows.net/";
    private static final String RECORD = "https://strecord.blob.core.windows.net/";

    private final AzureBlobStorageConfig config = new AzureBlobStorageConfig();

    private static StandardEnvironment on(String profile) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profile);
        return environment;
    }

    @Test
    void hostedUsesTheEndpointAndThePlatformIdentity() {
        BlobServiceClient files = config.blobServiceClient(on("pilot"), "", FILES);
        BlobServiceClient record = config.deletionRecordBlobServiceClient(on("pilot"), "", RECORD, FILES);

        assertEquals(FILES, files.getAccountUrl() + "/");
        assertEquals(RECORD, record.getAccountUrl() + "/");
    }

    @Test
    void hostedRefusesAConnectionString() {
        IllegalStateException refused = assertThrows(
                IllegalStateException.class, () -> config.blobServiceClient(on("pilot"), LOCAL, FILES));

        assertTrue(refused.getMessage().contains("BROWNIE_LOCAL_STORAGE_CONNECTION"), refused.getMessage());
    }

    @Test
    void hostedRefusesAMissingEndpoint() {
        assertThrows(IllegalStateException.class, () -> config.blobServiceClient(on("production"), "", ""));
        assertThrows(
                IllegalStateException.class,
                () -> config.deletionRecordBlobServiceClient(on("production"), "", "", FILES));
    }

    /**
     * The deletion record exists so that restoring the files cannot undo a
     * deletion. In one account it would be restored along with them, so the
     * two being the same is a configuration mistake serious enough to refuse
     * to start over.
     */
    @Test
    void hostedRefusesTheRecordSharingAnAccountWithTheFiles() {
        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> config.deletionRecordBlobServiceClient(on("pilot"), "", FILES, FILES));

        assertTrue(refused.getMessage().contains("different storage account"), refused.getMessage());
    }

    @Test
    void locallyTheConnectionStringIsUsedAndAnEndpointIsRefused() {
        BlobServiceClient client = config.blobServiceClient(on("local"), LOCAL, "");
        assertEquals("http://127.0.0.1:1/devstoreaccount1", client.getAccountUrl());

        assertThrows(IllegalStateException.class, () -> config.blobServiceClient(on("local"), LOCAL, FILES));
        assertThrows(IllegalStateException.class, () -> config.blobServiceClient(on("test"), "", ""));
    }
}
