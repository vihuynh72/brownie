package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimitClass;
import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimiter;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The MCP rate limit, counted per grant rather than per person: a
 * connected app can act for a workspace on behalf of several chats, and
 * the grant is the identity the chain has actually vetted. Registered
 * after the bearer filter in the MCP chain, so only authenticated calls
 * count and a refusal still arrives before the tool runs. Shares {@link
 * RateLimiter} with the browser-facing filter, so its class is tuned the
 * same way the others are.
 */
class McpRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpRateLimitFilter.class);

    private final RateLimiter rateLimiter;

    McpRateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the JSON-RPC POST is a tool call; a metadata GET on this
        // chain is not counted against the grant.
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().equals("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var caller = McpCallerContext.current();
        if (caller.isEmpty()) {
            // Unauthenticated or the public metadata route: this filter
            // counts nothing, and the chain's authorization layer answers
            // the unauthenticated case itself.
            filterChain.doFilter(request, response);
            return;
        }
        long waitSeconds = rateLimiter.secondsUntilAllowed("mcp grant " + caller.get().grantId(), RateLimitClass.MCP);
        if (waitSeconds == 0) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("Rate limit reached for MCP calls on {} {}.", request.getMethod(), request.getRequestURI());
        response.setHeader("Retry-After", Long.toString(waitSeconds));
        response.setStatus(429);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"rate_limited\",\"error_description\":\"Too many calls in a short time. Wait "
                        + waitSeconds + " seconds and try again.\"}");
    }
}
