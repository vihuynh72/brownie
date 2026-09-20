package io.github.vihuynh72.brownie.api.web.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final AtomicInteger reached = new AtomicInteger();

    @AfterEach
    void clearWhoIsAsking() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyHealthIsLeftUncountedNotEverythingBesideIt() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(oneAMinute());

        assertThat(statusOf(filter, "/actuator/env")).isEqualTo(200);
        assertThat(statusOf(filter, "/actuator/env")).isEqualTo(429);
        for (int i = 0; i < 5; i++) {
            assertThat(statusOf(filter, "/actuator/health")).isEqualTo(200);
            assertThat(statusOf(filter, "/actuator/health/readiness")).isEqualTo(200);
        }
        assertThat(statusOf(filter, "/actuator/healthy")).isEqualTo(429);
    }

    private int statusOf(RateLimitFilter filter, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> reached.incrementAndGet());
        return response.getStatus();
    }

    private static RateLimiter oneAMinute() {
        Map<RateLimitClass, Integer> all = new EnumMap<>(RateLimitClass.class);
        for (RateLimitClass limitClass : RateLimitClass.values()) {
            all.put(limitClass, 1);
        }
        return new RateLimiter(all, () -> 1L);
    }
}
