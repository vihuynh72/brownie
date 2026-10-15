package io.github.vihuynh72.brownie.api.mcp.oauth;

import java.util.OptionalLong;

/**
 * The only name the authorization server ever knows a person by:
 * {@code user:<brownie user id>}. Principal minimization writes it into
 * {@code principal_name} and the stored attributes in place of the OIDC
 * identity, so nothing under the authorization server's tables carries an
 * email, a display name or a provider claim.
 */
final class McpPrincipalName {

    private static final String PREFIX = "user:";

    private McpPrincipalName() {
    }

    static String of(long userId) {
        return PREFIX + userId;
    }

    /** The Brownie user id this principal name encodes, or empty when it is not one of ours. */
    static OptionalLong userIdOf(String principalName) {
        if (principalName == null || !principalName.startsWith(PREFIX)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(principalName.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
