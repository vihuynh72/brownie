package io.github.vihuynh72.brownie.core.mcp;

/**
 * How one tool call ended. REFUSED is a deliberate answer -- scope
 * missing, confirmation wrong, id in another workspace -- and ERROR is an
 * exception the call was not designed to produce.
 */
public enum McpToolCallOutcome {

    OK,
    REFUSED,
    ERROR
}
