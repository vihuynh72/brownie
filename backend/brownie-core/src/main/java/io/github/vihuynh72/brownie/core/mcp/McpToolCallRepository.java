package io.github.vihuynh72.brownie.core.mcp;

import java.util.List;

/**
 * Append-only record of MCP tool calls. {@code userId} on the read method
 * is the member-context requirement every workspace repository carries;
 * {@link #record} runs under the calling grant's own context because the
 * row belongs to that grant.
 */
public interface McpToolCallRepository {

    McpToolCall record(
            long workspaceId,
            long userId,
            long grantId,
            Long documentId,
            String tool,
            McpToolCallOutcome outcome,
            String code);

    /** The calls any grant made against one document, oldest first. */
    List<McpToolCall> findForDocument(long workspaceId, long userId, long documentId);
}
