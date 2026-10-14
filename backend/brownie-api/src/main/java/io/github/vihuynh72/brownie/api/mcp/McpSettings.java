package io.github.vihuynh72.brownie.api.mcp;

import java.util.regex.Pattern;

/**
 * The fixed public addresses MCP answers with: the origin a token
 * challenge, a metadata document and a download link all name, from the
 * configured {@code BROWNIE_PUBLIC_ORIGIN} -- never from a request's Host
 * header, so a forged Host cannot change the issuer a client is told to
 * trust.
 */
record McpSettings(String publicOrigin) {

    private static final Pattern ORIGIN_ONLY = Pattern.compile("^https?://[A-Za-z0-9.-]+(?::\\d{1,5})?$");

    McpSettings {
        if (publicOrigin == null || !ORIGIN_ONLY.matcher(publicOrigin).matches()) {
            throw new IllegalStateException(
                    "BROWNIE_PUBLIC_ORIGIN must be an absolute http(s) origin with no path, query, or fragment; got \"" + publicOrigin + "\".");
        }
    }

    /** The metadata document address every /mcp challenge names. */
    String protectedResourceMetadataUrl() {
        return publicOrigin + "/.well-known/oauth-protected-resource/mcp";
    }

    /** The resource identifier the protected-resource document and the authorization server share. */
    String resource() {
        return publicOrigin + "/mcp";
    }

    /** Where a chat client sends a person for the document on screen. */
    String documentUrl(long documentId) {
        return publicOrigin + "/documents/" + documentId;
    }

    String downloadUrl(String token) {
        return publicOrigin + "/api/v1/mcp-downloads/" + token;
    }
}
