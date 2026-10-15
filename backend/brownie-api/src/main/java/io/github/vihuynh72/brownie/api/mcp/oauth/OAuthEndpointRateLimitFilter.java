package io.github.vihuynh72.brownie.api.mcp.oauth;

import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimitClass;
import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimitFilter;
import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The ANONYMOUS rate class for the authorization-server chain. Every
 * request it sees is counted by address, signed in or not -- registration,
 * token, authorize and the metadata documents are all endpoints an
 * unauthenticated internet client can reach, so the address is the only
 * identity there is to bound. It runs at the head of the chain, before
 * any endpoint filter can answer, so a flood is refused before Spring's
 * machinery does any work.
 */
final class OAuthEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OAuthEndpointRateLimitFilter.class);

    private final RateLimiter rateLimiter;

    OAuthEndpointRateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String who = "address " + RateLimitFilter.networkOf(request.getRemoteAddr());
        long waitSeconds = rateLimiter.secondsUntilAllowed(who, RateLimitClass.ANONYMOUS);
        if (waitSeconds == 0) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("Rate limit reached for OAuth requests on {} {}.", request.getMethod(), request.getRequestURI());
        response.setHeader("Retry-After", Long.toString(waitSeconds));
        response.setStatus(429);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"temporarily_unavailable\",\"error_description\":\"Too many requests. Wait "
                        + waitSeconds + " seconds and try again.\"}");
    }
}
