package io.github.vihuynh72.brownie.core.mcp;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Persists download links. Creating one runs under the issuing caller's
 * workspace context; {@link #claim} runs without any member context at
 * all, through the database function that atomically checks the token
 * digest and marks the link used.
 */
public interface McpDownloadLinkRepository {

    McpDownloadLink create(
            long workspaceId,
            long userId,
            long grantId,
            long exportReceiptId,
            McpDownloadFile file,
            String tokenSha256,
            OffsetDateTime expiresAt);

    /**
     * Atomic read-and-mark: the live link for this token digest, already
     * stamped used, or empty for an unknown, spent, expired or
     * revoked-grant token.
     */
    Optional<ClaimedMcpDownload> claim(String tokenSha256);
}
