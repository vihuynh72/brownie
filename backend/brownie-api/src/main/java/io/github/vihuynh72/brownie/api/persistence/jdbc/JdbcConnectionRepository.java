package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.connector.Connection;
import io.github.vihuynh72.brownie.core.connector.ConnectionAccountMismatchException;
import io.github.vihuynh72.brownie.core.connector.ConnectionRepository;
import io.github.vihuynh72.brownie.core.connector.ConnectionState;
import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ProviderAccount;
import io.github.vihuynh72.brownie.core.connector.ProviderRevocation;
import io.github.vihuynh72.brownie.core.connector.ReconnectReason;
import io.github.vihuynh72.brownie.core.connector.SealedToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The table's own policies do most of the enforcing: a person sees and
 * changes only their own connections, only a workspace owner may create one,
 * and a disconnected row can no longer be changed at all; its constraints
 * keep a token present exactly while a connection is usable. No query here
 * returns the token columns except {@link #findToken}, so a listing can never
 * carry one by accident.
 */
@Repository
class JdbcConnectionRepository implements ConnectionRepository {

    private static final String COLUMNS = "id, workspace_id, user_id, access, account_id, account_email, granted_scopes, state,"
            + " reconnect_reason, token_issued_at, connected_at, disconnected_at, provider_revocation";

    private final JdbcTemplate jdbcTemplate;

    JdbcConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Connection> findForPerson(long workspaceId, long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM connector_connection WHERE workspace_id = ? AND user_id = ?"
                        + " ORDER BY connected_at DESC, id DESC",
                this::mapConnection,
                workspaceId,
                userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Connection> findOpen(long workspaceId, long userId, ConnectorAccess access) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return findOpenInTransaction(workspaceId, userId, access);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SealedToken> findToken(long workspaceId, long userId, long connectionId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT token_key_id, token_nonce, token_ciphertext FROM connector_connection"
                                + " WHERE workspace_id = ? AND user_id = ? AND id = ? AND state = 'ACTIVE'",
                        (rs, rowNum) -> new SealedToken(rs.getString("token_key_id"), rs.getBytes("token_nonce"), rs.getBytes("token_ciphertext")),
                        workspaceId,
                        userId,
                        connectionId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public Connection saveActive(
            long workspaceId, long userId, ConnectorAccess access, ProviderAccount account, List<String> grantedScopes, SealedToken token) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // One consent completion per person and kind of access at a time, so two arriving together cannot each insert.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('connector_connection:' || ?::text || ':' || ?::text || ':' || ?, 0))",
                rs -> null,
                workspaceId,
                userId,
                access.name());
        String scopes = String.join(" ", grantedScopes);
        Optional<Connection> open = findOpenInTransaction(workspaceId, userId, access);
        Connection saved;
        if (open.isPresent()) {
            if (!open.get().accountId().equals(account.id())) {
                throw new ConnectionAccountMismatchException(access);
            }
            saved = jdbcTemplate.queryForObject(
                    """
                    UPDATE connector_connection
                    SET account_email = ?, granted_scopes = ?, state = 'ACTIVE', reconnect_reason = NULL,
                        token_key_id = ?, token_nonce = ?, token_ciphertext = ?, token_issued_at = now(), updated_at = now()
                    WHERE workspace_id = ? AND user_id = ? AND id = ? AND state <> 'DISCONNECTED'
                    RETURNING
                    """ + " " + COLUMNS,
                    this::mapConnection,
                    account.email(),
                    scopes,
                    token.keyId(),
                    token.nonce(),
                    token.ciphertext(),
                    workspaceId,
                    userId,
                    open.get().id());
        } else {
            saved = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO connector_connection (workspace_id, user_id, provider, access, account_id, account_email,
                        granted_scopes, state, token_key_id, token_nonce, token_ciphertext, token_issued_at)
                    VALUES (?, ?, 'GOOGLE', ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, now())
                    RETURNING
                    """ + " " + COLUMNS,
                    this::mapConnection,
                    workspaceId,
                    userId,
                    access.name(),
                    account.id(),
                    account.email(),
                    scopes,
                    token.keyId(),
                    token.nonce(),
                    token.ciphertext());
        }
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "CONNECTOR_CONNECTED", "connector-connection", saved.id(),
                "{\"access\":\"" + access.name() + "\",\"renewed\":" + open.isPresent() + "}");
        return saved;
    }

    @Override
    @Transactional
    public Optional<Connection> requireReconnect(long workspaceId, long userId, long connectionId, ReconnectReason reason) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        """
                        UPDATE connector_connection
                        SET state = 'RECONNECT_REQUIRED', reconnect_reason = ?,
                            token_key_id = NULL, token_nonce = NULL, token_ciphertext = NULL, updated_at = now()
                        WHERE workspace_id = ? AND user_id = ? AND id = ? AND state = 'ACTIVE'
                        RETURNING
                        """ + " " + COLUMNS,
                        this::mapConnection,
                        reason.name(),
                        workspaceId,
                        userId,
                        connectionId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public Optional<Connection> disconnect(long workspaceId, long userId, long connectionId, ProviderRevocation revocation) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        Optional<Connection> disconnected = jdbcTemplate
                .query(
                        """
                        UPDATE connector_connection
                        SET state = 'DISCONNECTED', reconnect_reason = NULL,
                            token_key_id = NULL, token_nonce = NULL, token_ciphertext = NULL,
                            disconnected_at = now(), provider_revocation = ?, updated_at = now()
                        WHERE workspace_id = ? AND user_id = ? AND id = ? AND state <> 'DISCONNECTED'
                        RETURNING
                        """ + " " + COLUMNS,
                        this::mapConnection,
                        revocation.name(),
                        workspaceId,
                        userId,
                        connectionId)
                .stream()
                .findFirst();
        if (disconnected.isEmpty()) {
            return Optional.empty();
        }
        int grantsRevoked = jdbcTemplate.update(
                "UPDATE connector_resource_grant SET revoked_at = now(), revoked_reason = 'DISCONNECTED'"
                        + " WHERE workspace_id = ? AND connection_id = ? AND revoked_at IS NULL",
                workspaceId,
                connectionId);
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "CONNECTOR_DISCONNECTED", "connector-connection", connectionId,
                "{\"access\":\"" + disconnected.get().access().name() + "\",\"providerRevocation\":\"" + revocation.name()
                        + "\",\"grantsRevoked\":" + grantsRevoked + "}");
        return disconnected;
    }

    private Optional<Connection> findOpenInTransaction(long workspaceId, long userId, ConnectorAccess access) {
        return jdbcTemplate
                .query(
                        "SELECT " + COLUMNS + " FROM connector_connection"
                                + " WHERE workspace_id = ? AND user_id = ? AND access = ? AND state <> 'DISCONNECTED'",
                        this::mapConnection,
                        workspaceId,
                        userId,
                        access.name())
                .stream()
                .findFirst();
    }

    private Connection mapConnection(ResultSet rs, int rowNum) throws SQLException {
        String reconnectReason = rs.getString("reconnect_reason");
        String providerRevocation = rs.getString("provider_revocation");
        return new Connection(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("user_id"),
                ConnectorAccess.valueOf(rs.getString("access")),
                rs.getString("account_id"),
                rs.getString("account_email"),
                Arrays.stream(rs.getString("granted_scopes").split(" ")).filter(s -> !s.isEmpty()).toList(),
                ConnectionState.valueOf(rs.getString("state")),
                reconnectReason == null ? null : ReconnectReason.valueOf(reconnectReason),
                rs.getObject("token_issued_at", OffsetDateTime.class),
                rs.getObject("connected_at", OffsetDateTime.class),
                rs.getObject("disconnected_at", OffsetDateTime.class),
                providerRevocation == null ? null : ProviderRevocation.valueOf(providerRevocation));
    }
}
