package io.github.vihuynh72.brownie.api.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * The JSON-RPC boundary the SDK's own transport does not draw. The
 * stateless WebMvc transport turns every failure -- an unknown method, a
 * body it cannot parse, a tool's crash -- into HTTP 500 carrying a
 * serialized {@link McpError} Throwable, which leaks the whole stack to
 * any client and, worse, makes a legacy endpoint look broken rather than
 * merely old: a connector that probes {@code server/discover} first (as
 * OpenAI's does) only falls back to {@code initialize} when the endpoint
 * answers "method not found".
 *
 * <p>The seam the fix sits on is the handler the server sets on the
 * transport: this class delegates every {@link McpStatelessServerTransport}
 * call, and the handler it installs translates the SDK's errors into real
 * JSON-RPC error responses -- {@code -32601} for a method nobody serves
 * (the code the SDK handler itself raises), {@code -32603} for anything
 * else. The router filter then replaces whatever the transport itself
 * still manages to turn into a 5xx -- a parse failure happens before any
 * handler runs -- with the same clean {@code -32603} body.
 */
class McpJsonRpcErrorBoundary implements McpStatelessServerTransport {

    private static final Logger log = LoggerFactory.getLogger(McpJsonRpcErrorBoundary.class);

    private final McpStatelessServerTransport delegate;

    McpJsonRpcErrorBoundary(McpStatelessServerTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        delegate.setMcpHandler(new BoundaryHandler(handler));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    /**
     * Rewrites any 5xx the transport produced before or outside the
     * handler -- its catch-all serializes the exception into the body --
     * to the same internal-error answer a handler failure gets, so a
     * client never sees a serialized Throwable.
     */
    static ServerResponse rewriteServerErrors(ServerRequest request, HandlerFunction<ServerResponse> next)
            throws Exception {
        ServerResponse response;
        try {
            response = next.handle(request);
        } catch (Exception failure) {
            log.error("MCP request could not be handled", failure);
            response = null;
        }
        if (response == null || response.statusCode().is5xxServerError()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", null);
            body.put("error", Map.of("code", McpSchema.ErrorCodes.INTERNAL_ERROR, "message", "Internal error"));
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return response;
    }

    /**
     * Answers a real JSON-RPC error where the SDK handler would let an
     * exception escape: {@code -32601 Method not found} when the method
     * names nothing the server serves, {@code -32603 Internal error} for
     * every other failure, with the detail kept in the server log and out
     * of the body. Notifications get no error channel, so a failed one is
     * logged and answered empty like an unknown one.
     */
    private static final class BoundaryHandler implements McpStatelessServerHandler {

        private final McpStatelessServerHandler delegate;

        private BoundaryHandler(McpStatelessServerHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<McpSchema.JSONRPCResponse> handleRequest(
                McpTransportContext context, McpSchema.JSONRPCRequest request) {
            return delegate.handleRequest(context, request)
                    .onErrorResume(error -> Mono.just(answer(request, error)));
        }

        @Override
        public Mono<Void> handleNotification(
                McpTransportContext context, McpSchema.JSONRPCNotification notification) {
            return delegate.handleNotification(context, notification)
                    .onErrorResume(error -> {
                        log.error("MCP notification {} failed inside the handler", notification.method(), error);
                        return Mono.empty();
                    });
        }

        private static McpSchema.JSONRPCResponse answer(McpSchema.JSONRPCRequest request, Throwable error) {
            if (error instanceof McpError mcpError
                    && mcpError.getJsonRpcError().code() != null
                    && mcpError.getJsonRpcError().code() == McpSchema.ErrorCodes.METHOD_NOT_FOUND) {
                return McpSchema.JSONRPCResponse.error(request.id(),
                        new McpSchema.JSONRPCResponse.JSONRPCError(
                                McpSchema.ErrorCodes.METHOD_NOT_FOUND, "Method not found"));
            }
            log.error("MCP request {} failed inside the handler", request.method(), error);
            return McpSchema.JSONRPCResponse.error(request.id(),
                    new McpSchema.JSONRPCResponse.JSONRPCError(
                            McpSchema.ErrorCodes.INTERNAL_ERROR, "Internal error"));
        }
    }
}
