package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimitClass;
import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimiter;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class McpRateLimitFilterTest {

    private final AtomicInteger reached = new AtomicInteger();

    @AfterEach
    void clearWhoIsAsking() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void callsCountPerGrantAndOnlyPostMcpCounts() throws Exception {
        McpRateLimitFilter filter = new McpRateLimitFilter(oneAMinute());
        asGrant(7);
        assertThat(statusOf(filter, "POST", "/mcp")).isEqualTo(200);
        assertThat(statusOf(filter, "POST", "/mcp")).isEqualTo(429);
        // Only POST /mcp is a tool call; anything else on the chain is free.
        assertThat(statusOf(filter, "GET", "/mcp")).isEqualTo(200);
        assertThat(statusOf(filter, "POST", "/mcp/other")).isEqualTo(200);

        // Another grant is another bucket: one person's second token and a
        // connected app acting for the workspace are bounded separately.
        asGrant(9);
        assertThat(statusOf(filter, "POST", "/mcp")).isEqualTo(200);
        assertThat(statusOf(filter, "POST", "/mcp")).isEqualTo(429);
    }

    @Test
    void anUnauthenticatedRequestIsNotCounted() throws Exception {
        McpRateLimitFilter filter = new McpRateLimitFilter(oneAMinute());
        assertThat(statusOf(filter, "POST", "/mcp")).isEqualTo(200);
        assertThat(statusOf(filter, "POST", "/mcp")).isEqualTo(200);
    }

    private int statusOf(McpRateLimitFilter filter, String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> reached.incrementAndGet());
        return response.getStatus();
    }

    private static void asGrant(long grantId) {
        SecurityContextHolder.getContext().setAuthentication(
                new McpAuthentication(new McpCaller(1, 2, grantId, "test client", Set.of(McpScope.READ))));
    }

    private static RateLimiter oneAMinute() {
        Map<RateLimitClass, Integer> all = new EnumMap<>(RateLimitClass.class);
        for (RateLimitClass limitClass : RateLimitClass.values()) {
            all.put(limitClass, 1);
        }
        return new RateLimiter(all, () -> 1L);
    }
}
