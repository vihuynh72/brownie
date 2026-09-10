package io.github.vihuynh72.brownie.core.evidence;

import java.util.Optional;

public interface SourceSpanRepository {

    Optional<SourceSpan> find(long workspaceId, long userId, long spanId);

    SourceSpan create(
            long workspaceId, long userId, long sourceSnapshotId, String extractionParserVersion, EvidenceLocator locator, String excerptHash);
}
