package io.github.vihuynh72.brownie.api.storage.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import io.github.vihuynh72.brownie.core.artifact.BlobSizeLimitExceededException;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.UploadResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Wraps one Azure Blob Storage container (Azurite locally). Every object
 * key is opaque and server-generated before it ever reaches this class --
 * nothing here interprets or sanitizes a name.
 */
@Component
class AzureBlobStore implements BlobStore {

    private static final String CONTAINER_NAME = "artifacts";
    private static final int BUFFER_SIZE = 8192;

    private final BlobServiceClient blobServiceClient;

    AzureBlobStore(BlobServiceClient blobServiceClient) {
        this.blobServiceClient = blobServiceClient;
    }

    @Override
    public UploadResult writeAndDigest(String objectKey, InputStream content, long maxBytes) throws IOException {
        BlobContainerClient container = containerClient();
        try {
            container.createIfNotExists();
        } catch (BlobStorageException e) {
            throw new IOException("Failed to prepare the artifact storage container.", e);
        }

        BlockBlobClient blockBlobClient =
                container.getBlobClient(objectKey).getBlockBlobClient();
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        OutputStream out;
        try {
            // Handles content of unknown length by staging blocks as they
            // arrive, rather than needing the full size upfront -- the
            // mechanism that lets this stay a single streaming pass with
            // no in-memory buffering of the whole object.
            out = blockBlobClient.getBlobOutputStream(true);
        } catch (BlobStorageException e) {
            throw new IOException("Failed to open a blob output stream for " + objectKey, e);
        }
        try {
            int read;
            while ((read = content.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BlobSizeLimitExceededException(maxBytes);
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (BlobStorageException e) {
            throw new IOException("Blob upload failed for " + objectKey, e);
        }
        // Reached only after a fully successful read-through. Closing here
        // -- not in a finally block -- is deliberate: this is the one call
        // that actually commits the staged blocks into a real blob. Azure
        // (and Azurite) discard uncommitted staged blocks on their own, so
        // any exception above, including the size-limit check, leaves
        // nothing durable behind instead of a truncated object.
        out.close();
        return new UploadResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public Optional<Long> sizeOf(String objectKey) throws IOException {
        try {
            BlobClient blob = containerClient().getBlobClient(objectKey);
            if (!blob.exists()) {
                return Optional.empty();
            }
            return Optional.of(blob.getProperties().getBlobSize());
        } catch (BlobStorageException e) {
            throw new IOException("Failed to read blob metadata for " + objectKey, e);
        }
    }

    @Override
    public InputStream openStream(String objectKey) throws IOException {
        try {
            // A real streaming download (BlobInputStream pulls bytes from
            // Azure/Azurite on demand as its caller reads), not a call
            // that resolves the whole object into memory upfront.
            return containerClient().getBlobClient(objectKey).openInputStream();
        } catch (BlobStorageException e) {
            throw new IOException("Failed to open a read stream for " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) throws IOException {
        try {
            containerClient().getBlobClient(objectKey).deleteIfExists();
        } catch (BlobStorageException e) {
            throw new IOException("Failed to delete blob " + objectKey, e);
        }
    }

    private BlobContainerClient containerClient() {
        return blobServiceClient.getBlobContainerClient(CONTAINER_NAME);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM.", e);
        }
    }
}
