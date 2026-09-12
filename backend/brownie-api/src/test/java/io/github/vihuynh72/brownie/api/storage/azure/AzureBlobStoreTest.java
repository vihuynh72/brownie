package io.github.vihuynh72.brownie.api.storage.azure;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import io.github.vihuynh72.brownie.core.artifact.BlobAlreadyExistsException;
import io.github.vihuynh72.brownie.storage.azure.AzureBlobStore;
import io.github.vihuynh72.brownie.core.artifact.BlobSizeLimitExceededException;
import io.github.vihuynh72.brownie.core.artifact.UploadResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link AzureBlobStore} directly against a real Azurite -- no
 * Spring context, since this only needs a {@code BlobServiceClient} and
 * the class under test, both constructible by hand. The behavior this
 * class exists specifically to prove empirically, not just trust from a
 * source comment: an interrupted write -- the read side throwing partway
 * through, exactly what a genuinely disconnected client upload looks like
 * from this class's own point of view -- never leaves a durable, readable
 * blob behind at that key, whether the interruption is a stream failure
 * or this store's own size-limit check.
 */
@Testcontainers
class AzureBlobStoreTest {

    @Container
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0");

    @Test
    void writeAndDigestStoresRetrievableContentOnTheHappyPath() throws IOException {
        AzureBlobStore store = newStore();
        String key = newKey();
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        UploadResult result = store.writeAndDigest(key, new ByteArrayInputStream(content), 1024);

        assertThat(result.byteCount()).isEqualTo(content.length);
        assertThat(result.sha256Hex()).isEqualTo(sha256Hex(content));
        assertThat(store.sizeOf(key)).contains((long) content.length);
        try (InputStream stored = store.openStream(key)) {
            assertThat(stored.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void createOnlyWriteNeverReplacesExistingContent() throws IOException {
        AzureBlobStore store = newStore();
        String key = newKey();
        byte[] first = "first object".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement object".getBytes(StandardCharsets.UTF_8);

        store.writeNewAndDigest(key, new ByteArrayInputStream(first), 1024);

        assertThrows(
                BlobAlreadyExistsException.class,
                () -> store.writeNewAndDigest(key, new ByteArrayInputStream(replacement), 1024));
        try (InputStream stored = store.openStream(key)) {
            assertThat(stored.readAllBytes()).isEqualTo(first);
        }
    }

    @Test
    void anInterruptedReadLeavesNoDurableBlobBehind() throws IOException {
        AzureBlobStore store = newStore();
        String key = newKey();
        InputStream interrupted = failingAfter(5_000);

        assertThrows(IOException.class, () -> store.writeAndDigest(key, interrupted, 1_000_000));

        assertThat(store.sizeOf(key)).isEmpty();
    }

    @Test
    void exceedingTheDeclaredSizeLimitDuringWriteLeavesNoDurableBlobBehind() throws IOException {
        AzureBlobStore store = newStore();
        String key = newKey();
        InputStream tooLarge = repeating((byte) 'x', 10_000);

        assertThrows(BlobSizeLimitExceededException.class, () -> store.writeAndDigest(key, tooLarge, 1_000));

        assertThat(store.sizeOf(key)).isEmpty();
    }

    private static AzureBlobStore newStore() {
        BlobServiceClient client =
                new BlobServiceClientBuilder().connectionString(AZURITE.getConnectionString()).buildClient();
        return new AzureBlobStore(client);
    }

    private static String newKey() {
        return "test/" + UUID.randomUUID();
    }

    /** Delivers {@code goodBytes} of real content, then fails every subsequent read -- a stand-in for a client that goes away mid-upload. */
    private static InputStream failingAfter(int goodBytes) {
        return new InputStream() {
            private int remaining = goodBytes;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    throw new IOException("simulated client disconnect");
                }
                remaining--;
                return 'a';
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) {
                    throw new IOException("simulated client disconnect");
                }
                int n = Math.min(len, remaining);
                Arrays.fill(b, off, off + n, (byte) 'a');
                remaining -= n;
                return n;
            }
        };
    }

    private static InputStream repeating(byte value, int count) {
        byte[] buffer = new byte[count];
        Arrays.fill(buffer, value);
        return new ByteArrayInputStream(buffer);
    }

    private static String sha256Hex(byte[] content) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
