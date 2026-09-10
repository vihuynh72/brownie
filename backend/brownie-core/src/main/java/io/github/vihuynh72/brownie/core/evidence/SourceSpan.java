package io.github.vihuynh72.brownie.core.evidence;

import java.time.OffsetDateTime;

/**
 * One immutable, addressable citation into a {@code SourceSnapshot},
 * pinned to the exact extraction version active when it was created.
 * {@code extractionParserVersion} is what makes a later parser upgrade
 * safe: resolving this span always re-reads the specific extraction
 * version it names, never whichever one happens to be "latest" by the
 * time someone resolves it, so a parser change can never silently
 * reinterpret -- or break -- evidence created under the old one. {@code
 * excerptHash} (SHA-256 of the resolved excerpt text, hex-encoded, the
 * same algorithm {@code Artifact} already uses for its own bytes) is
 * recorded at creation time and re-checked on every resolution as a
 * defense-in-depth integrity check, not because the underlying data is
 * expected to ever actually change.
 */
public record SourceSpan(
        long id, long workspaceId, long sourceSnapshotId, String extractionParserVersion, EvidenceLocator locator, String excerptHash, OffsetDateTime createdAt) {
}
