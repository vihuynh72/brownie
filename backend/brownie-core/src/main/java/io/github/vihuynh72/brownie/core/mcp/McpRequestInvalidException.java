package io.github.vihuynh72.brownie.core.mcp;

/**
 * A grant-management request that could not be understood or satisfied as
 * asked -- a blank name, no scopes. Answers 400 like the other
 * request-validation exceptions.
 */
public class McpRequestInvalidException extends RuntimeException {

    public McpRequestInvalidException(String message) {
        super(message);
    }
}
