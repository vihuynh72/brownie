package io.github.vihuynh72.brownie.core.mcp;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Issues, lists, resolves and revokes MCP grants. The two credential
 * kinds arrive through different doors -- personal tokens are minted
 * here; OAuth client grants are recorded by {@link #grantOAuthAccess},
 * which the authorization server's consent flow calls -- but the tools,
 * the listing and revocation treat every kind alike.
 */
public class McpGrantService {

    private static final int NAME_LIMIT = 80;

    private final McpGrantRepository grantRepository;

    public McpGrantService(McpGrantRepository grantRepository) {
        this.grantRepository = grantRepository;
    }

    /**
     * Mints a personal token and records its grant. The returned token is
     * the only copy that ever exists -- callers must hand it to the owner
     * before it falls out of scope.
     */
    public IssuedPersonalToken issuePersonalToken(long workspaceId, long userId, String name, Set<McpScope> scopes) {
        if (name == null || name.isBlank() || name.length() > NAME_LIMIT) {
            throw new McpRequestInvalidException("A token name is required and stays under " + NAME_LIMIT + " characters.");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new McpRequestInvalidException("A token needs at least one scope.");
        }
        String token = McpTokenGeneration.newPersonalToken();
        McpGrant grant = grantRepository.create(
                workspaceId, userId, McpGrantKind.PERSONAL_TOKEN, name.trim(), null,
                EnumSet.copyOf(scopes), McpTokenGeneration.sha256Hex(token), McpTokenGeneration.hint(token));
        return new IssuedPersonalToken(grant.id(), token);
    }

    /**
     * Who a presented token belongs to: the live grant's identity, or
     * empty for an unknown, malformed or revoked token -- all three
     * indistinguishable on purpose.
     */
    public Optional<McpCaller> resolvePersonalToken(String presented) {
        if (!McpTokenGeneration.looksLikePersonalToken(presented)) {
            return Optional.empty();
        }
        return grantRepository.resolvePersonalToken(McpTokenGeneration.sha256Hex(presented))
                .map(grant -> new McpCaller(
                        grant.userId(), grant.workspaceId(), grant.grantId(), grant.name(), grant.scopes()));
    }

    public List<McpGrant> listForUser(long workspaceId, long userId) {
        return grantRepository.findAllForUser(workspaceId, userId);
    }

    /**
     * The grant a consent creates or refreshes: the registered client's
     * name becomes the grant's name, and the consented scopes replace the
     * live grant's on a re-consent.
     */
    public McpGrant grantOAuthAccess(
            long workspaceId, long userId, String clientName, String registeredClientId, Set<McpScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new McpRequestInvalidException("A grant needs at least one scope.");
        }
        return grantRepository.createOrRefreshOAuthGrant(
                workspaceId, userId, clientName, registeredClientId, EnumSet.copyOf(scopes));
    }

    /** The live OAuth grant behind an access token's authorization, for the resource server's caller resolution. */
    public Optional<ResolvedMcpGrant> findLiveOAuthGrant(long userId, String registeredClientId) {
        return grantRepository.findLiveOAuthGrant(userId, registeredClientId);
    }

    /** Touches the grant's last-used stamp, throttled in the database to at most one write a minute. */
    public void markUsed(long grantId) {
        grantRepository.markUsed(grantId);
    }

    /**
     * Marks the grant revoked. For an OAUTH_CLIENT grant the same call
     * also drops the authorization rows its access and refresh tokens
     * live in, so revocation ends the whole session at once rather than
     * waiting on token expiry.
     */
    public McpGrant revoke(long workspaceId, long userId, long grantId) {
        return grantRepository.revoke(workspaceId, userId, grantId)
                .orElseThrow(() -> new McpGrantNotFoundException(grantId));
    }
}
