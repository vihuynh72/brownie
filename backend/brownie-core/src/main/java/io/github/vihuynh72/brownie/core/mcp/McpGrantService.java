package io.github.vihuynh72.brownie.core.mcp;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Issues, lists, resolves and revokes MCP grants. In this slice the only
 * kind minted here is the personal token; OAuth client grants arrive
 * through the authorization server in a later slice and only ever meet
 * this service's {@link #revoke}, which treats every kind alike.
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
     * Marks the grant revoked. For an OAUTH_CLIENT grant this is the whole
     * operation in this slice: dropping the authorization rows the OAuth
     * slice adds is its own concern, and a revoked grant already fails
     * every token resolution.
     */
    public McpGrant revoke(long workspaceId, long userId, long grantId) {
        return grantRepository.revoke(workspaceId, userId, grantId)
                .orElseThrow(() -> new McpGrantNotFoundException(grantId));
    }
}
