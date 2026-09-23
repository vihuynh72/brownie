package io.github.vihuynh72.brownie.core.connector;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Whose token a ciphertext is. It is authenticated along with the token, so
 * a ciphertext copied into another person's row, another workspace, another
 * kind of access or another account does not decrypt at all rather than
 * quietly handing that person someone else's access.
 */
public record TokenBinding(long workspaceId, long userId, ConnectorAccess access, String accountId) {

    private static final String VERSION = "brownie-connector-token/v1";

    public TokenBinding {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(accountId, "accountId");
    }

    /** The exact bytes authenticated with the token. A NUL cannot occur in any of the parts, so the parts cannot run into each other. */
    public byte[] associatedData() {
        String joined = VERSION + '\0' + workspaceId + '\0' + userId + '\0' + access.name() + '\0' + accountId;
        return joined.getBytes(StandardCharsets.UTF_8);
    }
}
