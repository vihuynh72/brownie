package io.github.vihuynh72.brownie.api.web.ratelimit;

import io.github.vihuynh72.brownie.api.web.FilterProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Refuses a request once the person making it has used their minute's
 * allowance for that kind of request. It runs inside the security filter
 * chain, straight after the session has said who is asking and before
 * anything else in that chain answers: so a person is counted as
 * themselves and not as the address they happen to share with a whole
 * office, and so the requests that chain answers by itself (a sign-in
 * being started, a request with no session, a failed CSRF check) are
 * counted too. Those are most of what someone who is not signed in can
 * send, and each can leave a session row behind. Only someone who is not
 * signed in is counted by address.
 *
 * <p>Not limited: health and the error page. Not registered at all when
 * {@code brownie.rate-limit.enabled} is false.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /**
     * A route is recognised by its fixed words, and an identifier is
     * whatever stands between them. Asking for digits there would be asking
     * for less than the application accepts ({@code +7}, {@code 0x7} and a
     * number with a space after it all reach the same handler as {@code 7}),
     * and whatever the pattern missed would be counted as an ordinary change.
     */
    private static final String ID = "[^/]+";
    private static final Pattern MODEL = Pattern.compile(
            "^/api/v1/workspaces/" + ID + "/(?:documents/" + ID + "/(?:assist/execute|generations|generations/" + ID + "/resume)"
                    + "|jobs/" + ID + "/(?:resume|retry))$");
    private static final Pattern RENDER = Pattern.compile(
            "^/api/v1/workspaces/" + ID + "/(?:documents/" + ID + "/(?:validate|export|revisions/" + ID + "/compile)"
                    + "|templates/" + ID + "/versions)$");
    private static final Pattern UPLOAD = Pattern.compile(
            "^/api/v1/workspaces/" + ID + "/(?:uploads(?:/" + ID + "/(?:content|complete))?|artifacts/" + ID + "/extraction)$");

    private final RateLimiter rateLimiter;

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = routedPath(request);
        // Health only, not everything under /actuator: any other address there is refused for want of a session,
        // and a refusal of that kind writes a session row, which is exactly what this filter is here to count.
        return "OPTIONS".equals(request.getMethod())
                || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String person = signedInPerson();
        RateLimitClass limitClass = person == null ? RateLimitClass.ANONYMOUS : classify(request);
        String who = person == null ? "address " + networkOf(request.getRemoteAddr()) : person;

        long waitSeconds = rateLimiter.secondsUntilAllowed(who, limitClass);
        if (waitSeconds == 0) {
            filterChain.doFilter(request, response);
            return;
        }
        // Who it was stays out of the log: the class and the route are what an operator needs.
        log.warn("Rate limit reached for {} requests on {} {}.", limitClass, request.getMethod(), request.getRequestURI());
        response.setHeader("Retry-After", Long.toString(waitSeconds));
        FilterProblemWriter.write(
                request, response, 429, "Too Many Requests",
                "Too many requests in a short time. Wait " + waitSeconds + " seconds and try again.", "RATE_LIMITED");
    }

    static RateLimitClass classify(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return RateLimitClass.READ;
        }
        String path = routedPath(request);
        if (MODEL.matcher(path).matches()) {
            return RateLimitClass.MODEL;
        }
        if (RENDER.matcher(path).matches()) {
            return RateLimitClass.RENDER;
        }
        if (UPLOAD.matcher(path).matches()) {
            return RateLimitClass.UPLOAD;
        }
        return RateLimitClass.WRITE;
    }

    /**
     * The path as the application will route it: decoded, and without
     * anything after a semicolon. Matching the address as it was typed
     * instead would let {@code /validat%65} reach the validate handler while
     * being counted as an ordinary change, because routing decodes and a
     * pattern on the raw address does not.
     */
    static String routedPath(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request);
    }

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    /**
     * An IPv4 address as it is; an IPv6 address by its first 64 bits, which
     * is the smallest block one subscriber is ever given. Counting each of
     * its 2^64 addresses separately would give whoever holds one a new
     * allowance with every request.
     */
    static String networkOf(String address) {
        if (address == null || address.indexOf(':') < 0) {
            return String.valueOf(address);
        }
        try {
            // A literal address is parsed, never looked up; anything that is not one is kept as it came.
            String literal = address.indexOf('%') < 0 ? address : address.substring(0, address.indexOf('%'));
            if (!literal.matches("[0-9A-Fa-f:.]+")) {
                return address;
            }
            byte[] bytes = java.net.InetAddress.getByName(literal).getAddress();
            if (bytes.length != 16) {
                return address;
            }
            StringBuilder network = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                network.append(String.format("%02x%02x:", bytes[i] & 0xFF, bytes[i + 1] & 0xFF));
            }
            return network.append(":/64").toString();
        } catch (java.net.UnknownHostException e) {
            return address;
        }
    }

    /** Issuer and subject together, as everywhere else a person is identified; never shown or logged. */
    private static String signedInPerson() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        if (authentication.getPrincipal() instanceof OidcUser user && user.getIssuer() != null) {
            return "person " + user.getIssuer() + '\n' + user.getSubject();
        }
        return "person " + authentication.getName();
    }
}
