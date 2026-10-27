package io.github.vihuynh72.brownie.core.mcp;

import java.time.OffsetDateTime;

/** What the upload page may see without a session: where the link stands, when it dies, and who asked for it. */
public record PeekedFormUpload(long workspaceId, long uploadId, String state, OffsetDateTime expiresAt, String grantName) {}
