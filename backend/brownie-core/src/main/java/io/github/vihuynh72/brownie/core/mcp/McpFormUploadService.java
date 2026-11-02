package io.github.vihuynh72.brownie.core.mcp;

import java.util.Optional;

/**
 * Minting, spending and settling upload links. A link is created under the
 * calling member's context and presented to the person as a page address;
 * what the token is worth is decided by the claim function, so the route
 * never reads a link row without holding it.
 */
public class McpFormUploadService {

    /** A link lives fifteen minutes: long enough to find the form, short enough not to sit open. */
    public static final java.time.Duration LINK_TTL = java.time.Duration.ofMinutes(15);

    private final McpFormUploadRepository uploadRepository;

    public McpFormUploadService(McpFormUploadRepository uploadRepository) {
        this.uploadRepository = uploadRepository;
    }

    /** A fresh link for the caller's workspace, its token handed back once and stored only as a digest. */
    public IssuedFormUpload create(McpCaller caller, String fileName) {
        String token = McpTokenGeneration.newUploadToken();
        McpFormUpload row = uploadRepository.create(
                caller.workspaceId(), caller.userId(), caller.grantId(),
                fileName == null || fileName.isBlank() ? "form" : fileName,
                McpTokenGeneration.sha256Hex(token));
        return new IssuedFormUpload(row, token);
    }

    public Optional<McpFormUpload> findForWorkspace(McpCaller caller, long uploadId) {
        return uploadRepository.findForWorkspace(caller.workspaceId(), caller.userId(), uploadId);
    }

    public Optional<ClaimedFormUpload> claim(String presented) {
        return uploadRepository.claim(McpTokenGeneration.sha256Hex(presented));
    }

    public Optional<PeekedFormUpload> peek(String presented) {
        return uploadRepository.peek(McpTokenGeneration.sha256Hex(presented));
    }

    public void finish(long workspaceId, long uploadId, String state, Long templateId, String failureCode) {
        uploadRepository.finish(workspaceId, uploadId, state, templateId, failureCode);
    }

    /** The one time the presented value and its row are seen together. */
    public record IssuedFormUpload(McpFormUpload upload, String token) {}
}
