package io.github.vihuynh72.brownie.core.mcp;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One single-use address for one exported file. The token column is the
 * SHA-256 the claim lookup is asked about; the cleartext token exists
 * only in the URL the tool hands back.
 */
public record McpDownloadLink(
        long id,
        long workspaceId,
        long grantId,
        long exportReceiptId,
        McpDownloadFile file,
        OffsetDateTime expiresAt,
        OffsetDateTime usedAt,
        OffsetDateTime createdAt) {

    public McpDownloadLink {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("A link must expire after it is created.");
        }
    }
}
