package io.github.vihuynh72.brownie.core.mcp;

/**
 * The audit record's own entries for MCP-specific acts: a fill confirmed
 * through a chat app and an export made through one. Grant issue and
 * revoke are audited inside the grant repository itself, the way every
 * other repository writes its own audit rows.
 */
public interface McpAuditRepository {

    void fillConfirmed(long workspaceId, long userId, long documentId, long proposalId);

    void exported(long workspaceId, long userId, long documentId, long receiptId);

    /** A form arrived through an upload link: ids and the outcome state only, never the file's name. */
    void formUploaded(long workspaceId, long userId, long uploadId, String state);
}
