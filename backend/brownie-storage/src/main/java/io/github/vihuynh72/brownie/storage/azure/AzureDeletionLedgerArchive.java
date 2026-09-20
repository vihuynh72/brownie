package io.github.vihuynh72.brownie.storage.azure;

import com.azure.core.util.BinaryData;
import com.azure.json.JsonProviders;
import com.azure.json.JsonReader;
import com.azure.json.JsonToken;
import com.azure.json.JsonWriter;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import io.github.vihuynh72.brownie.core.artifact.BlobStoreUnavailableException;
import io.github.vihuynh72.brownie.core.retention.ArchivedDeletion;
import io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive;
import io.github.vihuynh72.brownie.core.retention.DeletionScope;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps carried-out deletions in a container of their own, apart from the
 * one that holds people's files, so that it can be given its own access
 * rules and its own backup arrangements: it is the one thing that must not
 * go back in time when everything else is restored.
 *
 * <p>One small JSON object per deletion, written only if it is not there
 * yet and never rewritten. Its name carries the moment of deletion as well
 * as the ledger id, because a restored database hands out ledger ids again
 * and a later deletion must not be mistaken for one already recorded.
 */
public class AzureDeletionLedgerArchive implements DeletionLedgerArchive {

    private static final String CONTAINER_NAME = "deletion-ledger";
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'");

    private final BlobServiceClient blobServiceClient;

    public AzureDeletionLedgerArchive(BlobServiceClient blobServiceClient) {
        this.blobServiceClient = blobServiceClient;
    }

    @Override
    public void add(ArchivedDeletion entry) throws IOException {
        BlobContainerClient container = containerClient();
        BlobClient blob = container.getBlobClient(nameOf(entry));
        try {
            container.createIfNotExists();
            BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromBytes(toJson(entry)))
                    .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
            blob.uploadWithResponse(options, null, null);
        } catch (BlobStorageException e) {
            // Already there, written by an earlier pass that died before it could say so. But a store says "conflict"
            // about other things too (a container that is being removed, for one), and the caller is about to tell
            // the database this deletion is safely on record. So it is only believed if the entry can be seen.
            if ((e.getStatusCode() == 409 || e.getStatusCode() == 412) && Boolean.TRUE.equals(blob.exists())) {
                return;
            }
            throw new IOException("Could not record deletion request " + entry.requestId() + " outside the database.", e);
        } catch (RuntimeException e) {
            throw new BlobStoreUnavailableException("The store that holds recorded deletions could not be reached.", e);
        }
    }

    @Override
    public List<ArchivedDeletion> readAll() throws IOException {
        BlobContainerClient container = containerClient();
        List<ArchivedDeletion> entries = new ArrayList<>();
        try {
            if (!container.exists()) {
                return entries;
            }
            for (BlobItem item : container.listBlobs()) {
                BlobClient blob = container.getBlobClient(item.getName());
                entries.add(fromJson(blob.downloadContent().toBytes(), item.getName()));
            }
        } catch (BlobStorageException e) {
            throw new IOException("Could not read the deletions recorded outside the database.", e);
        } catch (RuntimeException e) {
            throw new BlobStoreUnavailableException("The store that holds recorded deletions could not be reached.", e);
        }
        return entries;
    }

    static String nameOf(ArchivedDeletion entry) {
        return NAME_TIME.format(entry.purgedAt().withOffsetSameInstant(ZoneOffset.UTC)) + "-request-" + entry.requestId() + ".json";
    }

    static byte[] toJson(ArchivedDeletion entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter writer = JsonProviders.createWriter(out)) {
            writer.writeStartObject();
            writer.writeIntField("formatVersion", 1);
            writer.writeLongField("requestId", entry.requestId());
            writer.writeLongField("workspaceId", entry.workspaceId());
            writer.writeStringField("scope", entry.scope().name());
            writer.writeLongField("targetId", entry.targetId());
            writer.writeLongField("requestedByUserId", entry.requestedByUserId());
            writer.writeStringField("requestedAt", entry.requestedAt().toString());
            writer.writeStringField("purgedAt", entry.purgedAt().toString());
            if (entry.targetCreatedAt() != null) {
                writer.writeStringField("targetCreatedAt", entry.targetCreatedAt().toString());
            }
            writer.writeStringField("inventory", entry.inventoryJson());
            writer.writeEndObject();
        }
        return out.toByteArray();
    }

    static ArchivedDeletion fromJson(byte[] json, String name) throws IOException {
        Long requestId = null;
        Long workspaceId = null;
        String scope = null;
        Long targetId = null;
        Long requestedByUserId = null;
        String requestedAt = null;
        String purgedAt = null;
        String targetCreatedAt = null;
        String inventory = "{}";
        try (JsonReader reader = JsonProviders.createReader(json)) {
            if (reader.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Archived deletion " + name + " is not a JSON object.");
            }
            while (reader.nextToken() != JsonToken.END_OBJECT) {
                String field = reader.getFieldName();
                reader.nextToken();
                switch (field) {
                    case "requestId" -> requestId = reader.getLong();
                    case "workspaceId" -> workspaceId = reader.getLong();
                    case "scope" -> scope = reader.getString();
                    case "targetId" -> targetId = reader.getLong();
                    case "requestedByUserId" -> requestedByUserId = reader.getLong();
                    case "requestedAt" -> requestedAt = reader.getString();
                    case "purgedAt" -> purgedAt = reader.getString();
                    case "targetCreatedAt" -> targetCreatedAt = reader.getString();
                    case "inventory" -> inventory = reader.getString();
                    default -> reader.skipChildren();
                }
            }
        }
        if (requestId == null || workspaceId == null || scope == null || targetId == null || requestedByUserId == null
                || requestedAt == null || purgedAt == null) {
            throw new IOException("Archived deletion " + name + " is missing a field it cannot be applied without.");
        }
        try {
            return new ArchivedDeletion(
                    requestId, workspaceId, DeletionScope.valueOf(scope), targetId, requestedByUserId,
                    OffsetDateTime.parse(requestedAt), OffsetDateTime.parse(purgedAt),
                    targetCreatedAt == null ? null : OffsetDateTime.parse(targetCreatedAt), inventory);
        } catch (RuntimeException e) {
            throw new IOException("Archived deletion " + name + " could not be read: " + e.getMessage(), e);
        }
    }

    private BlobContainerClient containerClient() {
        return blobServiceClient.getBlobContainerClient(CONTAINER_NAME);
    }
}
