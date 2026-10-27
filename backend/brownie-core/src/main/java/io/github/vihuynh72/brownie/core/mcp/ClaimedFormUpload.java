package io.github.vihuynh72.brownie.core.mcp;

/**
 * What a successful claim hands the anonymous route: the upload row, the
 * grant it rides on, and the member to run as. The route trusts none of
 * the request and all of this -- it is the claim function's answer, so
 * every use-after claim is scoped like any member's call.
 */
public record ClaimedFormUpload(
        long workspaceId, long uploadId, long grantId, long userId, String fileName, String grantName) {}
