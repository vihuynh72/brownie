package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallOutcome;
import io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers 403 before a tool ever runs when the caller's grant does not
 * carry the scope that tool needs -- the tool name sits inside the
 * JSON-RPC body, not in the path, so the check has to open the envelope
 * itself. The parsed messages stay as a request attribute so the tool can
 * read the request's id without touching the body a second time, and the
 * body is re-wrapped for the MCP transport to read.
 *
 * <p>Runs after the bearer filter has authenticated, so a refusal here is
 * recorded against a known grant like any other tool refusal. A tool the
 * map does not name is left for the protocol layer to refuse as unknown.
 */
class McpScopeEnforcementFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpScopeEnforcementFilter.class);
    private static final int MAX_BODY_BYTES = 1 << 20;

    private final ObjectMapper objectMapper;
    private final McpToolCallRepository toolCallRepository;

    McpScopeEnforcementFilter(ObjectMapper objectMapper, McpToolCallRepository toolCallRepository) {
        this.objectMapper = objectMapper;
        this.toolCallRepository = toolCallRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        byte[] body = readBounded(request);
        List<McpJsonRpc> messages = parse(body);
        request.setAttribute(McpJsonRpc.ATTRIBUTE, messages);

        var callerOptional = McpCallerContext.current();
        if (callerOptional.isEmpty()) {
            // Nothing to scope a call against: the request is either the
            // public metadata document or an unauthenticated call the
            // authorization layer answers itself.
            filterChain.doFilter(new ReusableBodyRequest(request, body), response);
            return;
        }
        McpCaller caller = callerOptional.get();

        McpScope missing = messages.stream()
                .map(McpJsonRpc::toolName)
                .filter(name -> name != null && BrownieMcpToolScopes.REQUIRED.containsKey(name))
                .map(name -> Map.entry(name, BrownieMcpToolScopes.REQUIRED.get(name)))
                .filter(entry -> !caller.has(entry.getValue()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (missing == null) {
            filterChain.doFilter(new ReusableBodyRequest(request, body), response);
            return;
        }

        String tool = messages.stream().map(McpJsonRpc::toolName).filter(java.util.Objects::nonNull).findFirst().orElse("tools/call");
        try {
            toolCallRepository.record(
                    caller.workspaceId(), caller.userId(), caller.grantId(), null, tool,
                    McpToolCallOutcome.REFUSED, McpToolRefusal.INSUFFICIENT_SCOPE);
        } catch (RuntimeException e) {
            log.warn("Tool-call record for a scope refusal could not be written", e);
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"" + missing.token() + "\"");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"insufficient_scope\",\"error_description\":\"This credential does not carry the '"
                + missing.token() + "' scope that " + tool + " needs.\"}");
    }

    private byte[] readBounded(HttpServletRequest request) throws IOException {
        // The container-level body limit has already bounded this read; the
        // array cap here only keeps the second, cached copy from trusting it.
        try (var in = request.getInputStream()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            // More than the cap: the envelope is refused at the container
            // limit anyway; an empty parse lets the transport answer its
            // own error rather than this filter holding the whole body.
            return body.length > MAX_BODY_BYTES ? new byte[0] : body;
        }
    }

    private List<McpJsonRpc> parse(byte[] body) {
        if (body.length == 0) {
            return List.of();
        }
        try {
            return McpJsonRpc.parse(objectMapper.readTree(body));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** A request whose body has been read, serving it again to whatever asks next. */
    private static final class ReusableBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private ReusableBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
