package io.github.vihuynh72.brownie.core.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The narrow storage contract a blob adapter (Azurite locally, Azure Blob
 * Storage once a hosted adapter exists) must satisfy. Nothing here knows
 * about workspaces, artifacts, or authorization -- an object key is an
 * opaque string the caller already decided.
 */
public interface BlobStore {

    /**
     * Writes {@code content} to {@code objectKey}, computing its size and
     * SHA-256 digest as the same bytes stream through, without ever
     * buffering the whole object in memory. Throws {@link
     * BlobSizeLimitExceededException} the moment more than {@code
     * maxBytes} have been read, and leaves nothing durable behind for that
     * object when it does.
     */
    UploadResult writeAndDigest(String objectKey, InputStream content, long maxBytes) throws IOException;

    /** Empty when no object exists at this key. */
    Optional<Long> sizeOf(String objectKey) throws IOException;

    /**
     * Streams the object's content back for inspection. A real streaming
     * download, not a full in-memory read -- the returned stream pulls
     * bytes on demand as its caller reads them.
     */
    InputStream openStream(String objectKey) throws IOException;

    /** A no-op, not an error, when no object exists at this key -- callers may call this defensively without checking existence first. */
    void delete(String objectKey) throws IOException;
}
