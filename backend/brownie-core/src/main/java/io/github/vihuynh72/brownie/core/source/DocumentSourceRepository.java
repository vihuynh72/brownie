package io.github.vihuynh72.brownie.core.source;

import java.util.List;
import java.util.Optional;

/** Every method takes workspace and user context explicitly, the same tenant-scoped pattern every other repository in this codebase follows. */
public interface DocumentSourceRepository {

    /** Links a snapshot to a document, or returns the link that already exists -- linking twice is a no-op, not a duplicate. */
    DocumentSource link(long workspaceId, long userId, long documentId, long sourceSnapshotId);

    /** Every source linked to this document, most recently attached first. */
    List<DocumentSource> findForDocument(long workspaceId, long userId, long documentId);

    /** This document's link to this snapshot, if it has one. */
    Optional<DocumentSource> find(long workspaceId, long userId, long documentId, long sourceSnapshotId);
}
