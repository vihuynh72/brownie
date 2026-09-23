package io.github.vihuynh72.brownie.core.connector;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A person's connection to their outside account for one kind of access, as
 * Brownie records it. It never carries the token itself: that is read on its
 * own, only when a request is about to use it.
 *
 * <p>{@code grantedScopes} is what the provider said it granted, which a
 * person can make narrower than what was asked for. {@code reconnectReason}
 * is set exactly when the state is {@link ConnectionState#RECONNECT_REQUIRED};
 * {@code disconnectedAt} and {@code providerRevocation} exactly when it is
 * {@link ConnectionState#DISCONNECTED}.
 */
public record Connection(
        long id,
        long workspaceId,
        long userId,
        ConnectorAccess access,
        String accountId,
        String accountEmail,
        List<String> grantedScopes,
        ConnectionState state,
        ReconnectReason reconnectReason,
        OffsetDateTime tokenIssuedAt,
        OffsetDateTime connectedAt,
        OffsetDateTime disconnectedAt,
        ProviderRevocation providerRevocation) {

    public Connection {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(connectedAt, "connectedAt");
        grantedScopes = List.copyOf(grantedScopes);
    }

    /** Usable now, or waiting for its person to connect again; anything but a disconnected record. */
    public boolean isOpen() {
        return state != ConnectionState.DISCONNECTED;
    }
}
