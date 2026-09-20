package io.github.vihuynh72.brownie.storage.azure;

import com.azure.storage.blob.BlobServiceClient;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.BlobStoreUnavailableException;
import io.github.vihuynh72.brownie.core.retention.ArchivedDeletion;
import io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive;
import io.github.vihuynh72.brownie.core.retention.DeletionScope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Nothing listens on the port this client is pointed at, which is what a
 * store that is down looks like from here. No container is needed for it.
 */
class AzureBlobStoreUnreachableTest {

    // Azurite's published development account, as in .env.example; the endpoint is the only part that matters here.
    private static final String NOBODY_HOME = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
            + "BlobEndpoint=http://127.0.0.1:1/devstoreaccount1;";

    private final BlobServiceClient client = AzureBlobStorageConfig.clientFor(NOBODY_HOME);

    /**
     * Every operation says the same specific thing, and says it within
     * seconds. Before the client's retries were bounded one request held a
     * person's connection open for about 45 seconds, and what came back was
     * an unchecked transport exception no caller was written to expect.
     */
    @Test
    void aStoreThatCannotBeReachedIsReportedAsUnavailablePromptlyByEveryOperation() {
        BlobStore store = new AzureBlobStore(client);
        assertTimeoutPreemptively(Duration.ofSeconds(40), () -> {
            assertThrows(BlobStoreUnavailableException.class, () -> store.sizeOf("workspace-1/object"));
            assertThrows(BlobStoreUnavailableException.class, () -> store.openStream("workspace-1/object"));
            assertThrows(BlobStoreUnavailableException.class, () -> store.delete("workspace-1/object"));
            assertThrows(BlobStoreUnavailableException.class,
                    () -> store.writeAndDigest("workspace-1/object", new ByteArrayInputStream(new byte[] {1, 2, 3}), 100));
        });
    }

    @Test
    void theDeletionRecordSaysTheSameWhenItsStoreIsAway() {
        DeletionLedgerArchive archive = new AzureDeletionLedgerArchive(client);
        ArchivedDeletion entry = entry();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            assertThrows(BlobStoreUnavailableException.class, () -> archive.add(entry));
            assertThrows(BlobStoreUnavailableException.class, archive::readAll);
        });
    }

    @Test
    void anEntryReadsBackExactlyAsItWasWrittenAndIsNamedByWhenItHappened() throws Exception {
        ArchivedDeletion entry = entry();

        ArchivedDeletion readBack = AzureDeletionLedgerArchive.fromJson(AzureDeletionLedgerArchive.toJson(entry), "name");

        assertEquals(entry, readBack);
        // A restored database hands out ledger ids again, so the id alone must never be the name.
        assertEquals("20260301T120002000000Z-request-41.json", AzureDeletionLedgerArchive.nameOf(entry));
        assertArrayEquals(AzureDeletionLedgerArchive.toJson(entry), AzureDeletionLedgerArchive.toJson(readBack));
    }

    @Test
    void anEntryThatDoesNotSayWhatWasDeletedIsRefusedNotGuessedAt() {
        byte[] incomplete = "{\"formatVersion\":1,\"requestId\":41,\"scope\":\"DOCUMENT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> AzureDeletionLedgerArchive.fromJson(incomplete, "broken.json"));
        byte[] unknownScope = new String(toJsonUnchecked(entry()), java.nio.charset.StandardCharsets.UTF_8)
                .replace("DOCUMENT", "EVERYTHING").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> AzureDeletionLedgerArchive.fromJson(unknownScope, "odd.json"));
    }

    private static byte[] toJsonUnchecked(ArchivedDeletion entry) {
        try {
            return AzureDeletionLedgerArchive.toJson(entry);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ArchivedDeletion entry() {
        OffsetDateTime asked = OffsetDateTime.parse("2026-03-01T12:00:01Z");
        return new ArchivedDeletion(
                41, 7, DeletionScope.DOCUMENT, 99, 3, asked, asked.plusSeconds(1), asked.minusDays(12).plusNanos(123_456_000), "{\"rowsRemoved\":12}");
    }
}
