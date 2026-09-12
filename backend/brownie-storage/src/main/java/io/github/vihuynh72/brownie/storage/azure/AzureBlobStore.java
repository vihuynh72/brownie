package io.github.vihuynh72.brownie.storage.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import io.github.vihuynh72.brownie.core.artifact.BlobAlreadyExistsException;
import io.github.vihuynh72.brownie.core.artifact.BlobSizeLimitExceededException;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.UploadResult;

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
public class AzureBlobStore implements BlobStore {

    private static final String CONTAINER_NAME = "artifacts";
    private static final int BUFFER_SIZE = 8192;

    private final BlobServiceClient blobServiceClient;

    public AzureBlobStore(BlobServiceClient blobServiceClient) {
        this.blobServiceClient = blobServiceClient;
    }

    @Override
    public UploadResult writeAndDigest(String objectKey, InputStream content, long maxBytes) throws IOException {
        return write(objectKey, content, maxBytes, true);
    }

    @Override
    public UploadResult writeNewAndDigest(String objectKey, InputStream content, long maxBytes) throws IOException {
        return write(objectKey, content, maxBytes, false);
    }

    private UploadResult write(String objectKey, InputStream content, long maxBytes, boolean overwrite) throws IOException {
        BlobContainerClient container = containerClient();
        try {
            container.createIfNotExists();
        } catch (BlobStorageException e) {
            throw new IOException("Failed to prepare the artifact storage container.", e);
        }

        BlockBlobClient blockBlobClient = container.getBlobClient(objectKey).getBlockBlobClient();
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        OutputStream out;
        try {
            out = blockBlobClient.getBlobOutputStream(overwrite);
        } catch (BlobStorageException e) {
            throw writeFailure(objectKey, overwrite, e);
        } catch (IllegalArgumentException e) {
            // The Azure SDK's create-only convenience overload checks
            // existence before opening its stream and reports that expected
            // conflict as IllegalArgumentException. Confirm the object still
            // exists before translating it, so malformed keys keep their
            // normal caller-visible validation failure.
            if (!overwrite && blockBlobClient.exists()) {
                throw new BlobAlreadyExistsException(objectKey, e);
            }
            throw e;
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
            out.close();
        } catch (BlobStorageException e) {
            throw writeFailure(objectKey, overwrite, e);
        }
        // Closing only after the complete read commits all staged blocks. A
        // failed read leaves no durable partial object in Azure or Azurite.
        return new UploadResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static IOException writeFailure(String objectKey, boolean overwrite, BlobStorageException failure) {
        if (!overwrite && (failure.getStatusCode() == 409 || failure.getStatusCode() == 412)) {
            return new BlobAlreadyExistsException(objectKey, failure);
        }
        return new IOException("Blob upload failed for " + objectKey, failure);
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
