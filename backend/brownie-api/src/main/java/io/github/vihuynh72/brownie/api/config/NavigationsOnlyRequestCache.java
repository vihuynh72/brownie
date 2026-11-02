package io.github.vihuynh72.brownie.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Which request the session keeps as "where the person was" for after
 * sign-in. Only a real browser navigation counts -- GET, a path the API
 * and the sign-in machinery do not own, and when the browser says what
 * kind of request it is making, a top-level navigation -- so a fetch the
 * web app makes, a session probe like {@code /api/v1/me} above all, never
 * wins the slot and sends the person to raw JSON after signing in. Deep
 * links and the MCP authorize resume still save.
 *
 * <p>Sessions that already hold a stale API saved request from before
 * this rule existed keep it under the session attribute, so {@code
 * getRequest} refuses to hand one of those back too; with nothing
 * returned the success handler falls through to the web app's root.
 */
final class NavigationsOnlyRequestCache implements RequestCache {

    private static final RequestMatcher NAVIGATIONS_ONLY = request -> "GET".equals(request.getMethod())
            && !request.getRequestURI().startsWith("/api/")
            && !request.getRequestURI().equals("/logout")
            && !request.getRequestURI().startsWith("/oauth2/")
            && (request.getHeader("Sec-Fetch-Mode") == null
                    || "navigate".equals(request.getHeader("Sec-Fetch-Mode")))
            && request.getHeader("X-Requested-With") == null;

    private final HttpSessionRequestCache delegate = new HttpSessionRequestCache();

    NavigationsOnlyRequestCache() {
        delegate.setRequestMatcher(NAVIGATIONS_ONLY);
    }

    @Override
    public void saveRequest(HttpServletRequest request, HttpServletResponse response) {
        delegate.saveRequest(request, response);
    }

    @Override
    public SavedRequest getRequest(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = delegate.getRequest(request, response);
        if (saved != null && isApi(saved)) {
            delegate.removeRequest(request, response);
            return null;
        }
        return saved;
    }

    @Override
    public HttpServletRequest getMatchingRequest(HttpServletRequest request, HttpServletResponse response) {
        return delegate.getMatchingRequest(request, response);
    }

    @Override
    public void removeRequest(HttpServletRequest request, HttpServletResponse response) {
        delegate.removeRequest(request, response);
    }

    private static boolean isApi(SavedRequest saved) {
        try {
            String path = new URI(saved.getRedirectUrl()).getPath();
            return path != null && path.startsWith("/api/");
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
