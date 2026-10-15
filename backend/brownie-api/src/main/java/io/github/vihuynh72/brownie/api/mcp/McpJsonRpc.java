package io.github.vihuynh72.brownie.api.mcp;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The pieces of a JSON-RPC envelope that application code reads: its
 * method, and for a {@code tools/call} the tool named. The scope filter,
 * which reads the body anyway for the scope check, parks the whole parsed
 * message on the request so nothing downstream has to parse it again. A
 * batch body is a list of these.
 */
record McpJsonRpc(String method, String toolName) {

    static final String ATTRIBUTE = McpJsonRpc.class.getName() + ".MESSAGES";

    /** Parses a request body into messages; unparseable or non-RPC bodies yield an empty list and pass through to the framework's own error. */
    static List<McpJsonRpc> parse(JsonNode root) {
        if (root == null) {
            return List.of();
        }
        if (root.isArray()) {
            return root.valueStream().map(McpJsonRpc::one).toList();
        }
        return List.of(one(root));
    }

    private static McpJsonRpc one(JsonNode message) {
        String method = message.path("method").isString() ? message.path("method").stringValue() : null;
        String toolName = "tools/call".equals(method) && message.path("params").path("name").isString()
                ? message.path("params").path("name").stringValue()
                : null;
        return new McpJsonRpc(method, toolName);
    }
}
