package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import java.util.Optional;

/** Resolves {@code brownie_pat_} tokens against the grant's stored SHA-256. */
class PersonalTokenCallerResolver implements McpCallerResolver {

    private final McpGrantService grantService;

    PersonalTokenCallerResolver(McpGrantService grantService) {
        this.grantService = grantService;
    }

    @Override
    public Optional<McpCaller> resolve(String bearerToken) {
        return grantService.resolvePersonalToken(bearerToken);
    }
}
