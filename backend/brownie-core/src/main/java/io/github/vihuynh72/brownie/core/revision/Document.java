package io.github.vihuynh72.brownie.core.revision;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A workspace-owned document pinned to one immutable template version.
 * Its current revision is a pointer only; document content belongs to the
 * immutable {@link DocumentRevision} rows in its history.
 */
public record Document(
        long id,
        long workspaceId,
        String title,
        long templateId,
        long templateVersionId,
        long currentRevisionId,
        OffsetDateTime createdAt) {

    public Document {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Document title must not be blank.");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
