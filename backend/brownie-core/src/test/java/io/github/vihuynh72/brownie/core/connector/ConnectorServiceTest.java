package io.github.vihuynh72.brownie.core.connector;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorServiceTest {

    private static final long WORKSPACE = 7;
    private static final long PERSON = 3;
    private static final String DRIVE_SCOPE = "drive-files-scope";
    private static final String CALENDAR_SCOPE = "calendar-events-scope";

    private final InMemoryConnections connections = new InMemoryConnections();
    private final BindingCheckingCipher cipher = new BindingCheckingCipher();
    private final ScriptedProvider provider = new ScriptedProvider();
    private final ConnectorService service = new ConnectorService(connections, cipher, provider);

    @Test
    void aCompletedConsentIsStoredEncryptedAndBoundToItsAccountAndNothingElseIsKept() {
        provider.nextExchange = new ProviderTokens("access-1", "refresh-1", Set.of(DRIVE_SCOPE, "openid"));

        UsableConnection usable = service.completeConsent(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "code", "verifier");
        Connection connection = usable.connection();

        assertEquals("access-1", usable.accessToken(), "the access token issued with the consent is handed on for this request");
        assertEquals(ConnectionState.ACTIVE, connection.state());
        assertEquals("account-a", connection.accountId());
        assertEquals(List.of(DRIVE_SCOPE, "openid"), connection.grantedScopes());
        assertEquals(List.of("code:verifier"), provider.exchanges);
        assertEquals(List.of("access-1"), provider.described, "the account is asked of the new access token");
        SealedToken stored = connections.tokens.get(connection.id());
        assertFalse(new String(stored.ciphertext(), StandardCharsets.UTF_8).contains("refresh-1"), "never the token itself");
        assertEquals("refresh-1", cipher.open(stored, new TokenBinding(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "account-a")));
        assertThrows(UnreadableTokenException.class,
                () -> cipher.open(stored, new TokenBinding(WORKSPACE, 99, ConnectorAccess.DRIVE_FILES, "account-a")),
                "a ciphertext copied to someone else does not open");
    }

    @Test
    void aConsentMissingItsPermissionOrItsRefreshTokenStoresNothing() {
        provider.nextExchange = new ProviderTokens("access-1", "refresh-1", Set.of("openid"));
        assertThrows(ConnectorPermissionNotGrantedException.class,
                () -> service.completeConsent(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "code", "verifier"));

        provider.nextExchange = new ProviderTokens("access-1", "refresh-1", Set.of());
        assertThrows(ConnectorPermissionNotGrantedException.class,
                () -> service.completeConsent(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "code", "verifier"),
                "a provider that names no permissions has not granted the one needed");

        provider.nextExchange = new ProviderTokens("access-1", null, Set.of(DRIVE_SCOPE));
        assertThrows(ConnectorConsentIncompleteException.class,
                () -> service.completeConsent(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "code", "verifier"));

        assertTrue(connections.rows.isEmpty());
        assertTrue(provider.revoked.isEmpty(), "a refused consent is not revoked: that would take back everything else too");
    }

    @Test
    void consentingAgainFromTheSameAccountRenewsTheSameConnectionAndFromAnotherAccountIsRefused() {
        provider.nextExchange = new ProviderTokens("access-1", "refresh-1", Set.of(DRIVE_SCOPE));
        Connection first = service.completeConsent(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "code", "verifier").connection();

        provider.nextExchange = new ProviderTokens("access-2", "refresh-2", Set.of(DRIVE_SCOPE));
        Connection renewed = service.completeConsent(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "code", "verifier").connection();
        assertEquals(first.id(), renewed.id());
        assertEquals("refresh-2", cipher.open(connections.tokens.get(first.id()),
                new TokenBinding(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "account-a")));

        provider.nextExchange = new ProviderTokens("access-3", "refresh-3", Set.of(DRIVE_SCOPE));
        provider.nextAccount = new ProviderAccount("account-b", "b@example.org");
        assertThrows(ConnectionAccountMismatchException.class,
                () -> service.completeConsent(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "code", "verifier"));
        assertEquals(1, connections.rows.size());
        assertEquals("refresh-2", cipher.open(connections.tokens.get(first.id()),
                new TokenBinding(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES, "account-a")), "the connection is untouched");
    }

    @Test
    void usingAConnectionRefreshesFromTheStoredTokenEveryTime() {
        connect(ConnectorAccess.CALENDAR_EVENTS, CALENDAR_SCOPE, "refresh-cal");
        provider.nextRefresh = new ProviderTokens("fresh-access", null, Set.of(CALENDAR_SCOPE));

        UsableConnection usable = service.use(WORKSPACE, PERSON, ConnectorAccess.CALENDAR_EVENTS);

        assertEquals("fresh-access", usable.accessToken());
        assertEquals(List.of("refresh-cal"), provider.refreshes);
        assertFalse(usable.toString().contains("fresh-access"));
    }

    @Test
    void aRejectedTokenWipesTheConnectionOnceAndTheNextRequestDoesNotAskTheProviderAgain() {
        Connection connection = connect(ConnectorAccess.DRIVE_FILES, DRIVE_SCOPE, "refresh-1");
        provider.refreshRejected = true;

        ConnectionReconnectRequiredException first = assertThrows(ConnectionReconnectRequiredException.class,
                () -> service.use(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES));
        assertEquals(ReconnectReason.TOKEN_REJECTED, first.reason());
        assertEquals(ConnectionState.RECONNECT_REQUIRED, connections.rows.get(connection.id()).state());
        assertNull(connections.tokens.get(connection.id()), "a token the provider refuses is not kept");

        ConnectionReconnectRequiredException second = assertThrows(ConnectionReconnectRequiredException.class,
                () -> service.use(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES));
        assertEquals(ReconnectReason.TOKEN_REJECTED, second.reason());
        assertEquals(1, provider.refreshes.size(), "no second trip to a provider that already said no");
    }

    @Test
    void aTokenThatNoLongerDecryptsOrAPermissionNoLongerGrantedMeansReconnecting() {
        Connection drive = connect(ConnectorAccess.DRIVE_FILES, DRIVE_SCOPE, "refresh-1");
        cipher.keyChanged = true;
        assertEquals(ReconnectReason.TOKEN_UNREADABLE, assertThrows(ConnectionReconnectRequiredException.class,
                () -> service.use(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES)).reason());
        assertEquals(ReconnectReason.TOKEN_UNREADABLE, connections.rows.get(drive.id()).reconnectReason());
        assertTrue(provider.refreshes.isEmpty());
        cipher.keyChanged = false;

        connect(ConnectorAccess.CALENDAR_EVENTS, CALENDAR_SCOPE, "refresh-cal");
        provider.nextRefresh = new ProviderTokens("fresh", null, Set.of("openid"));
        assertEquals(ReconnectReason.PERMISSION_MISSING, assertThrows(ConnectionReconnectRequiredException.class,
                () -> service.use(WORKSPACE, PERSON, ConnectorAccess.CALENDAR_EVENTS)).reason());
    }

    @Test
    void aRefreshThatNamesNoPermissionsIsNotReadAsHavingLostThem() {
        connect(ConnectorAccess.CALENDAR_EVENTS, CALENDAR_SCOPE, "refresh-cal");
        provider.nextRefresh = new ProviderTokens("fresh", null, Set.of());

        assertEquals("fresh", service.use(WORKSPACE, PERSON, ConnectorAccess.CALENDAR_EVENTS).accessToken());
    }

    @Test
    void usingAConnectionThatDoesNotExistOrWaitsForItsPersonNeverReachesTheProvider() {
        assertThrows(ConnectionNotFoundException.class, () -> service.use(WORKSPACE, PERSON, ConnectorAccess.DRIVE_FILES));
        assertTrue(provider.refreshes.isEmpty());
    }

    @Test
    void disconnectingRevokesEachUsableTokenAndWipesEveryConnectionWhateverTheProviderSays() {
        Connection drive = connect(ConnectorAccess.DRIVE_FILES, DRIVE_SCOPE, "refresh-drive");
        Connection calendar = connect(ConnectorAccess.CALENDAR_EVENTS, CALENDAR_SCOPE, "refresh-cal");
        provider.revokeUnavailableFor = "refresh-cal";

        List<Connection> result = service.disconnectAll(WORKSPACE, PERSON);

        assertEquals(2, result.size());
        assertEquals(ProviderRevocation.REVOKED, connections.rows.get(drive.id()).providerRevocation());
        assertEquals(ProviderRevocation.FAILED, connections.rows.get(calendar.id()).providerRevocation());
        assertEquals(ConnectionState.DISCONNECTED, connections.rows.get(calendar.id()).state());
        assertNull(connections.tokens.get(calendar.id()), "wiped even though the provider could not be told");
        assertEquals(List.of("refresh-drive", "refresh-cal"), provider.revoked);

        assertTrue(service.disconnectAll(WORKSPACE, PERSON).isEmpty(), "asking twice changes nothing");
    }

    @Test
    void aConnectionAlreadyWaitingForItsPersonIsDisconnectedWithoutAskingTheProvider() {
        Connection drive = connect(ConnectorAccess.DRIVE_FILES, DRIVE_SCOPE, "refresh-drive");
        connections.requireReconnect(WORKSPACE, PERSON, drive.id(), ReconnectReason.TOKEN_REJECTED);

        service.disconnectAll(WORKSPACE, PERSON);

        assertEquals(ProviderRevocation.NOT_NEEDED, connections.rows.get(drive.id()).providerRevocation());
        assertTrue(provider.revoked.isEmpty());
    }

    @Test
    void tokensAreReadBeforeAWorkspaceIsDeletedAndRevokedOnlyAfterward() {
        connect(ConnectorAccess.DRIVE_FILES, DRIVE_SCOPE, "refresh-drive");
        Connection calendar = connect(ConnectorAccess.CALENDAR_EVENTS, CALENDAR_SCOPE, "refresh-cal");
        connections.requireReconnect(WORKSPACE, PERSON, calendar.id(), ReconnectReason.TOKEN_REJECTED);

        PendingRevocations pending = service.prepareForWorkspaceDeletion(WORKSPACE, PERSON);
        assertEquals(List.of("refresh-drive"), pending.tokens());
        assertTrue(provider.revoked.isEmpty(), "nothing is revoked until the deletion has happened");
        assertFalse(pending.toString().contains("refresh"));

        provider.revokeUnavailableFor = "refresh-drive";
        assertEquals(1, service.revokeAfterWorkspaceDeletion(pending));
    }

    private Connection connect(ConnectorAccess access, String scope, String refreshToken) {
        provider.nextExchange = new ProviderTokens("access", refreshToken, Set.of(scope));
        return service.completeConsent(WORKSPACE, PERSON, access, "code", "verifier").connection();
    }

    /** Reversible, and refuses a binding other than the one it sealed with, as authenticated encryption does. */
    private static final class BindingCheckingCipher implements ConnectorTokenCipher {

        boolean keyChanged;

        @Override
        public SealedToken seal(String token, TokenBinding binding) {
            byte[] plain = token.getBytes(StandardCharsets.UTF_8);
            byte[] reversed = new byte[plain.length];
            for (int i = 0; i < plain.length; i++) {
                reversed[i] = plain[plain.length - 1 - i];
            }
            return new SealedToken("k1", binding.associatedData(), reversed);
        }

        @Override
        public String open(SealedToken sealed, TokenBinding binding) {
            if (keyChanged || !Arrays.equals(sealed.nonce(), binding.associatedData())) {
                throw new UnreadableTokenException("not for this binding");
            }
            byte[] reversed = sealed.ciphertext();
            byte[] plain = new byte[reversed.length];
            for (int i = 0; i < reversed.length; i++) {
                plain[i] = reversed[reversed.length - 1 - i];
            }
            return new String(plain, StandardCharsets.UTF_8);
        }
    }

    private static final class ScriptedProvider implements ConnectorOAuthClient {

        ProviderTokens nextExchange;
        ProviderTokens nextRefresh = new ProviderTokens("fresh-access", null, Set.of());
        ProviderAccount nextAccount = new ProviderAccount("account-a", "a@example.org");
        boolean refreshRejected;
        String revokeUnavailableFor;
        final List<String> exchanges = new ArrayList<>();
        final List<String> described = new ArrayList<>();
        final List<String> refreshes = new ArrayList<>();
        final List<String> revoked = new ArrayList<>();

        @Override
        public Set<String> requiredScopes(ConnectorAccess access) {
            return access == ConnectorAccess.DRIVE_FILES ? Set.of(DRIVE_SCOPE) : Set.of(CALENDAR_SCOPE);
        }

        @Override
        public ProviderTokens exchange(ConnectorAccess access, String authorizationCode, String codeVerifier) {
            exchanges.add(authorizationCode + ":" + codeVerifier);
            return nextExchange;
        }

        @Override
        public ProviderTokens refresh(ConnectorAccess access, String refreshToken) {
            refreshes.add(refreshToken);
            if (refreshRejected) {
                throw new ProviderTokenRejectedException("invalid_grant");
            }
            return nextRefresh;
        }

        @Override
        public ProviderAccount describeAccount(ConnectorAccess access, String accessToken) {
            described.add(accessToken);
            return nextAccount;
        }

        @Override
        public void revoke(String token) {
            revoked.add(token);
            if (token.equals(revokeUnavailableFor)) {
                throw new ProviderUnavailableException("unreachable");
            }
        }
    }

    /** Keeps the state rules the table's constraints keep: a token exactly while usable, disconnected rows frozen. */
    private static final class InMemoryConnections implements ConnectionRepository {

        final Map<Long, Connection> rows = new HashMap<>();
        final Map<Long, SealedToken> tokens = new HashMap<>();
        private long nextId = 1;

        @Override
        public List<Connection> findForPerson(long workspaceId, long userId) {
            return rows.values().stream()
                    .filter(c -> c.workspaceId() == workspaceId && c.userId() == userId)
                    .sorted((a, b) -> Long.compare(a.id(), b.id()))
                    .toList();
        }

        @Override
        public Optional<Connection> findOpen(long workspaceId, long userId, ConnectorAccess access) {
            return findForPerson(workspaceId, userId).stream().filter(c -> c.access() == access && c.isOpen()).findFirst();
        }

        @Override
        public Optional<SealedToken> findToken(long workspaceId, long userId, long connectionId) {
            Connection connection = rows.get(connectionId);
            if (connection == null || connection.userId() != userId || connection.state() != ConnectionState.ACTIVE) {
                return Optional.empty();
            }
            return Optional.ofNullable(tokens.get(connectionId));
        }

        @Override
        public Connection saveActive(
                long workspaceId, long userId, ConnectorAccess access, ProviderAccount account, List<String> grantedScopes, SealedToken token) {
            Optional<Connection> open = findOpen(workspaceId, userId, access);
            if (open.isPresent() && !open.get().accountId().equals(account.id())) {
                throw new ConnectionAccountMismatchException(access);
            }
            long id = open.map(Connection::id).orElseGet(() -> nextId++);
            Connection saved = new Connection(id, workspaceId, userId, access, account.id(), account.email(), grantedScopes,
                    ConnectionState.ACTIVE, null, OffsetDateTime.now(), OffsetDateTime.now(), null, null);
            rows.put(id, saved);
            tokens.put(id, token);
            return saved;
        }

        @Override
        public Optional<Connection> requireReconnect(long workspaceId, long userId, long connectionId, ReconnectReason reason) {
            Connection connection = rows.get(connectionId);
            if (connection == null || connection.state() != ConnectionState.ACTIVE) {
                return Optional.empty();
            }
            Connection changed = new Connection(connection.id(), workspaceId, userId, connection.access(), connection.accountId(),
                    connection.accountEmail(), connection.grantedScopes(), ConnectionState.RECONNECT_REQUIRED, reason,
                    connection.tokenIssuedAt(), connection.connectedAt(), null, null);
            rows.put(connectionId, changed);
            tokens.remove(connectionId);
            return Optional.of(changed);
        }

        @Override
        public Optional<Connection> disconnect(long workspaceId, long userId, long connectionId, ProviderRevocation revocation) {
            Connection connection = rows.get(connectionId);
            if (connection == null || !connection.isOpen()) {
                return Optional.empty();
            }
            Connection changed = new Connection(connection.id(), workspaceId, userId, connection.access(), connection.accountId(),
                    connection.accountEmail(), connection.grantedScopes(), ConnectionState.DISCONNECTED, null,
                    connection.tokenIssuedAt(), connection.connectedAt(), OffsetDateTime.now(), revocation);
            rows.put(connectionId, changed);
            tokens.remove(connectionId);
            return Optional.of(changed);
        }
    }

    @Test
    void theBindingCoversEveryPartThatSaysWhoseTokenItIs() {
        byte[] base = new TokenBinding(1, 2, ConnectorAccess.DRIVE_FILES, "acct").associatedData();
        assertArrayEquals(base, new TokenBinding(1, 2, ConnectorAccess.DRIVE_FILES, "acct").associatedData());
        assertFalse(Arrays.equals(base, new TokenBinding(9, 2, ConnectorAccess.DRIVE_FILES, "acct").associatedData()));
        assertFalse(Arrays.equals(base, new TokenBinding(1, 9, ConnectorAccess.DRIVE_FILES, "acct").associatedData()));
        assertFalse(Arrays.equals(base, new TokenBinding(1, 2, ConnectorAccess.CALENDAR_EVENTS, "acct").associatedData()));
        assertFalse(Arrays.equals(base, new TokenBinding(1, 2, ConnectorAccess.DRIVE_FILES, "other").associatedData()));
        // A workspace id that ends where a user id begins cannot be confused for another split of the same digits.
        assertFalse(Arrays.equals(new TokenBinding(12, 3, ConnectorAccess.DRIVE_FILES, "a").associatedData(),
                new TokenBinding(1, 23, ConnectorAccess.DRIVE_FILES, "a").associatedData()));
    }
}
