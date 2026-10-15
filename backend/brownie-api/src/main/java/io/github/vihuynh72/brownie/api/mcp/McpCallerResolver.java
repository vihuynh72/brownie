package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import java.util.Optional;

/**
 * Turns a bearer token presented to {@code /mcp} into the caller it
 * authenticates as. Each credential kind gets its own implementation --
 * the personal token's SHA-256 lookup here, the OAuth access token's
 * authorization lookup beside it -- and the chain asks each in turn, so
 * the endpoint never cares which kind of token arrived.
 */
public interface McpCallerResolver {

    /** The live caller this token resolves to, or empty for a token this resolver does not own. */
    Optional<McpCaller> resolve(String bearerToken);
}
