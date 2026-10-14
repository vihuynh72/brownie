package io.github.vihuynh72.brownie.core.mcp;

import java.util.Objects;

/**
 * The result of a successful one-time claim: which export receipt the
 * link points at and which file of it, the link id so the download route
 * can leave an audit trail, and the grant owner's user id -- the member
 * identity the anonymous route's receipt and artifact reads run under. A
 * link that is spent, expired, or whose grant was since revoked claims to
 * nothing -- empty, like a wrong token.
 */
public record ClaimedMcpDownload(
        long workspaceId,
        long linkId,
        long exportReceiptId,
        McpDownloadFile file,
        long userId) {

    public ClaimedMcpDownload {
        Objects.requireNonNull(file, "file");
    }
}
