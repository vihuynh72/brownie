package io.github.vihuynh72.brownie.core.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The rules of a person's connection to their outside account, independent of
 * which provider it is and of how its HTTP calls are made.
 *
 * <p>A consent counts only when it granted every permission its kind of
 * access needs, came with a refresh token Brownie can keep, and came from the
 * same account the access is already connected to, if it is. Using a
 * connection always asks the provider for a fresh access token first: that is
 * the moment Brownie learns the person took its access away, and the answer
 * is to stop and wait for them rather than to retry. Disconnecting asks the
 * provider to forget the access and wipes Brownie's copy of the token whatever
 * the provider answers, so a provider that cannot be reached never leaves
 * Brownie able to act on a person's behalf after they said stop.
 *
 * <p>A consent that is refused is never revoked here. Revoking at the provider
 * takes back everything the person granted this application, including any
 * other kind of access they had connected, so it happens only when they ask
 * to disconnect.
 */
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);

    private final ConnectionRepository connectionRepository;
    private final ConnectorTokenCipher tokenCipher;
    private final ConnectorOAuthClient oauthClient;

    public ConnectorService(ConnectionRepository connectionRepository, ConnectorTokenCipher tokenCipher, ConnectorOAuthClient oauthClient) {
        this.connectionRepository = Objects.requireNonNull(connectionRepository, "connectionRepository");
        this.tokenCipher = Objects.requireNonNull(tokenCipher, "tokenCipher");
        this.oauthClient = Objects.requireNonNull(oauthClient, "oauthClient");
    }

    public List<Connection> connections(long workspaceId, long userId) {
        return connectionRepository.findForPerson(workspaceId, userId);
    }

    /**
     * Turns the code the provider returned after the person agreed into a
     * stored, usable connection, with the access token issued alongside it
     * for whatever this same request does next.
     */
    public UsableConnection completeConsent(long workspaceId, long userId, ConnectorAccess access, String authorizationCode, String codeVerifier) {
        Objects.requireNonNull(access, "access");
        ProviderTokens tokens = oauthClient.exchange(access, authorizationCode, codeVerifier);
        if (tokens.grantedScopes().isEmpty() || !tokens.grantedScopes().containsAll(oauthClient.requiredScopes(access))) {
            throw new ConnectorPermissionNotGrantedException(access);
        }
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            throw new ConnectorConsentIncompleteException(access);
        }
        ProviderAccount account = oauthClient.describeAccount(access, tokens.accessToken());
        Optional<Connection> open = connectionRepository.findOpen(workspaceId, userId, access);
        if (open.isPresent() && !open.get().accountId().equals(account.id())) {
            throw new ConnectionAccountMismatchException(access);
        }
        SealedToken sealed = tokenCipher.seal(tokens.refreshToken(), new TokenBinding(workspaceId, userId, access, account.id()));
        List<String> scopes = tokens.grantedScopes().stream().sorted().toList();
        return new UsableConnection(connectionRepository.saveActive(workspaceId, userId, access, account, scopes, sealed), tokens.accessToken());
    }

    /**
     * A usable connection with a fresh access token for this request, or
     * {@link ConnectionReconnectRequiredException} once the connection cannot
     * be used, which is recorded so that the next request does not ask the
     * provider again.
     */
    public UsableConnection use(long workspaceId, long userId, ConnectorAccess access) {
        Connection connection = connectionRepository
                .findOpen(workspaceId, userId, access)
                .orElseThrow(() -> new ConnectionNotFoundException(access));
        if (connection.state() == ConnectionState.RECONNECT_REQUIRED) {
            throw new ConnectionReconnectRequiredException(access, connection.reconnectReason());
        }
        // Disconnected or waiting for its person between the two reads: the same answer the next request would get.
        SealedToken sealed = connectionRepository
                .findToken(workspaceId, userId, connection.id())
                .orElseThrow(() -> new ConnectionNotFoundException(access));
        String refreshToken;
        try {
            refreshToken = tokenCipher.open(sealed, bindingOf(connection));
        } catch (UnreadableTokenException e) {
            throw needsReconnect(connection, ReconnectReason.TOKEN_UNREADABLE);
        }
        ProviderTokens fresh;
        try {
            fresh = oauthClient.refresh(access, refreshToken);
        } catch (ProviderTokenRejectedException e) {
            throw needsReconnect(connection, ReconnectReason.TOKEN_REJECTED);
        }
        // A refresh that names its permissions and leaves one out means the person narrowed what Brownie may do.
        if (!fresh.grantedScopes().isEmpty() && !fresh.grantedScopes().containsAll(oauthClient.requiredScopes(access))) {
            throw needsReconnect(connection, ReconnectReason.PERMISSION_MISSING);
        }
        return new UsableConnection(connection, fresh.accessToken());
    }

    /**
     * Disconnects every open connection this person has in this workspace,
     * each revoked at the provider first and wiped here whatever the provider
     * said. Returns the connections as they are now; empty when nothing was
     * open, so asking twice is harmless.
     */
    public List<Connection> disconnectAll(long workspaceId, long userId) {
        List<Connection> disconnected = new ArrayList<>();
        for (Connection connection : connectionRepository.findForPerson(workspaceId, userId)) {
            if (!connection.isOpen()) {
                continue;
            }
            ProviderRevocation revocation = revokeAtProvider(connection);
            connectionRepository.disconnect(workspaceId, userId, connection.id(), revocation).ifPresent(disconnected::add);
        }
        return disconnected;
    }

    /**
     * Before a workspace is deleted: the refresh tokens of this person's usable
     * connections, decrypted and held in memory, so that they can be revoked
     * at the provider once the deletion (which removes the rows holding them)
     * has actually happened. A deletion that is refused therefore leaves the
     * person's connections exactly as they were.
     */
    public PendingRevocations prepareForWorkspaceDeletion(long workspaceId, long userId) {
        List<String> tokens = new ArrayList<>();
        for (Connection connection : connectionRepository.findForPerson(workspaceId, userId)) {
            if (connection.state() != ConnectionState.ACTIVE) {
                continue;
            }
            Optional<SealedToken> sealed = connectionRepository.findToken(workspaceId, userId, connection.id());
            if (sealed.isEmpty()) {
                continue;
            }
            try {
                tokens.add(tokenCipher.open(sealed.get(), bindingOf(connection)));
            } catch (UnreadableTokenException | ConnectorNotConfiguredException e) {
                // Nothing can be revoked that cannot be read; the row, and with it the ciphertext, goes with the workspace.
                log.warn("A {} connection's token could not be read before its workspace was deleted.", connection.access());
            }
        }
        return new PendingRevocations(tokens);
    }

    /** After the workspace is gone: asks the provider to forget each token. Returns how many it could not reach. */
    public int revokeAfterWorkspaceDeletion(PendingRevocations pending) {
        int failed = 0;
        for (String token : pending.tokens()) {
            try {
                oauthClient.revoke(token);
            } catch (ProviderUnavailableException | ProviderMisconfiguredException | ConnectorNotConfiguredException e) {
                failed++;
                log.warn("A deleted workspace's Google access could not be revoked at Google ({}).", e.getClass().getSimpleName());
            }
        }
        return failed;
    }

    /**
     * The provider refused an access token it had issued moments before, which
     * means the person took Brownie's access away in between: recorded as a
     * refused token, like a refusal at refresh, and returned for the caller to
     * throw.
     */
    public ConnectionReconnectRequiredException tokenRefusedDuringUse(Connection connection) {
        return needsReconnect(connection, ReconnectReason.TOKEN_REJECTED);
    }

    public Set<String> requiredScopes(ConnectorAccess access) {
        return oauthClient.requiredScopes(access);
    }

    private ProviderRevocation revokeAtProvider(Connection connection) {
        if (connection.state() != ConnectionState.ACTIVE) {
            return ProviderRevocation.NOT_NEEDED;
        }
        Optional<SealedToken> sealed = connectionRepository.findToken(connection.workspaceId(), connection.userId(), connection.id());
        if (sealed.isEmpty()) {
            return ProviderRevocation.NOT_NEEDED;
        }
        try {
            oauthClient.revoke(tokenCipher.open(sealed.get(), bindingOf(connection)));
            return ProviderRevocation.REVOKED;
        } catch (UnreadableTokenException | ProviderUnavailableException | ProviderMisconfiguredException | ConnectorNotConfiguredException e) {
            log.warn("A {} connection could not be revoked at the provider ({}); it is disconnected here regardless.",
                    connection.access(), e.getClass().getSimpleName());
            return ProviderRevocation.FAILED;
        }
    }

    private ConnectionReconnectRequiredException needsReconnect(Connection connection, ReconnectReason reason) {
        connectionRepository.requireReconnect(connection.workspaceId(), connection.userId(), connection.id(), reason);
        log.info("A {} connection needs to be connected again ({}).", connection.access(), reason);
        return new ConnectionReconnectRequiredException(connection.access(), reason);
    }

    private static TokenBinding bindingOf(Connection connection) {
        return new TokenBinding(connection.workspaceId(), connection.userId(), connection.access(), connection.accountId());
    }
}
