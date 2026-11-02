package io.github.vihuynh72.brownie.core.mcp;

import java.time.OffsetDateTime;

/**
 * One upload link's row as the member's own read sees it: who asked, the
 * file it brought, where it stands, and the template it became when it
 * succeeded. The presented token never appears here -- only the row's
 * state.
 */
public record McpFormUpload(
        long id,
        long workspaceId,
        long userId,
        long grantId,
        String fileName,
        String state,
        Long templateId,
        String failureCode,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime usedAt) {}
