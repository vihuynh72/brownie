package io.github.vihuynh72.brownie.storage.s3;

import io.github.vihuynh72.brownie.core.retention.*;
import io.github.vihuynh72.brownie.core.artifact.BlobAlreadyExistsException;
import io.github.vihuynh72.brownie.core.artifact.BlobStoreUnavailableException;
import io.github.vihuynh72.brownie.storage.azure.AzureDeletionLedgerArchive;
import java.io.*;
import java.util.*;

/** Uses the existing archive format so moving storage does not change restore semantics. */
public final class S3DeletionLedgerArchive implements DeletionLedgerArchive {
    private final S3BlobStore store;

    public S3DeletionLedgerArchive(S3BlobStore store) { this.store = store; }

    @Override
    public void add(ArchivedDeletion entry) throws IOException {
        String key = AzureDeletionLedgerArchive.nameOf(entry);
        byte[] json = AzureDeletionLedgerArchive.toJson(entry);
        try {
            store.writeNewAndDigest(key, new ByteArrayInputStream(json), json.length);
        } catch (BlobAlreadyExistsException conflict) {
            try (InputStream in = store.openStream(key)) {
                if (!Arrays.equals(json, in.readNBytes(json.length + 1))) {
                    throw new IOException("The recorded deletion differs from this request.", conflict);
                }
            }
        }
    }

    @Override
    public List<ArchivedDeletion> readAll() throws IOException {
        List<ArchivedDeletion> entries = new ArrayList<>();
        try {
            for (var page : store.client.listObjectsV2Paginator(r -> r.bucket(store.bucket))) {
                for (var object : page.contents()) {
                    try (InputStream in = store.openStream(object.key())) {
                        entries.add(AzureDeletionLedgerArchive.fromJson(in.readAllBytes(), object.key()));
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new BlobStoreUnavailableException("Could not read recorded deletions.", e);
        }
        return entries;
    }
}
