package io.github.vihuynh72.brownie.api.mcp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

/**
 * The pieces of a JSON-RPC envelope that application code reads: its
 * method, and for a {@code tools/call} the tool named, plus the request's
 * id -- which the MCP SDK does not hand a tool method, so the scope
 * filter, which reads the body anyway for the scope check, parks the
 * whole parsed message where the tool can pick it up. A batch body is a
 * list of these; a notification has no id, which is itself meaningful:
 * idempotent work keys on the id only when one was sent.
 */
record McpJsonRpc(String method, String toolName, String requestId) {

    static final String ATTRIBUTE = McpJsonRpc.class.getName() + ".MESSAGES";

    /** The id of the JSON-RPC request being served, or null for a notification or a non-call request. */
    static String currentRequestId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return null;
        }
        return currentRequestId(servlet.getRequest());
    }

    @SuppressWarnings("unchecked")
    static String currentRequestId(HttpServletRequest request) {
        Object messages = request.getAttribute(ATTRIBUTE);
        if (!(messages instanceof List<?> list)) {
            return null;
        }
        return list.stream()
                .filter(McpJsonRpc.class::isInstance)
                .map(McpJsonRpc.class::cast)
                .map(McpJsonRpc::requestId)
                .filter(id -> id != null)
                .findFirst()
                .orElse(null);
    }

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
        JsonNode id = message.get("id");
        String requestId = id == null || id.isNull() ? null : (id.isString() ? id.stringValue() : id.toString());
        return new McpJsonRpc(method, toolName, requestId);
    }
}
