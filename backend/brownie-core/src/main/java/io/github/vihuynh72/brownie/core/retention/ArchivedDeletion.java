package io.github.vihuynh72.brownie.core.retention;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One deletion that was carried out, as it is kept outside the database:
 * what was deleted, who asked and when, when it happened, and how much
 * went with it. Ids, times and counts only, like the ledger row it is
 * copied from; nothing a person wrote.
 *
 * <p>The target is named by its id <em>and</em> the moment it was created.
 * Ids are handed out again after a restore, so an id alone could come to
 * mean a different document; the two together never do. It is absent only
 * when the target was already gone when the deletion was carried out.
 */
public record ArchivedDeletion(
        long requestId,
        long workspaceId,
        DeletionScope scope,
        long targetId,
        long requestedByUserId,
        OffsetDateTime requestedAt,
        OffsetDateTime purgedAt,
        OffsetDateTime targetCreatedAt,
        String inventoryJson) {

    public ArchivedDeletion {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(purgedAt, "purgedAt");
        Objects.requireNonNull(inventoryJson, "inventoryJson");
        if (requestId <= 0 || workspaceId <= 0 || targetId <= 0 || requestedByUserId <= 0) {
            throw new IllegalArgumentException("An archived deletion's ids are positive.");
        }
    }
}
