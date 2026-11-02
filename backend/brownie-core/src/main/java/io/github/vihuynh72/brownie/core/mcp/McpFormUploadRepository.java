package io.github.vihuynh72.brownie.core.mcp;

import java.util.Optional;

/**
 * The upload links the form-intake tools mint and the anonymous route
 * spends. {@link #create} and {@link #findForWorkspace} run under the
 * member's own workspace context; {@link #claim} and {@link #peek} run
 * without any member context at all -- the presented token is the whole
 * credential, and both are backed by definer-side functions.
 */
public interface McpFormUploadRepository {

    McpFormUpload create(long workspaceId, long userId, long grantId, String fileName, String tokenSha256);

    Optional<McpFormUpload> findForWorkspace(long workspaceId, long userId, long uploadId);

    Optional<ClaimedFormUpload> claim(String tokenSha256);

    Optional<PeekedFormUpload> peek(String tokenSha256);

    void finish(long workspaceId, long uploadId, String state, Long templateId, String failureCode);
}
