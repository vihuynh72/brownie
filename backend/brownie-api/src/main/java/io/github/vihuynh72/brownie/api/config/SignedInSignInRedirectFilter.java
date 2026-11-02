package io.github.vihuynh72.brownie.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Keeps a person who is already signed in out of the identity provider's
 * round trip. The web app's own links to {@code /oauth2/authorization/entra}
 * start a fresh OAuth authorization request no matter who asks -- a second
 * click on "Sign in" while the session still lives would bounce through
 * Entra and land on the same page the person was already on. Before the
 * redirect filter builds that request, this one looks at the session the
 * request carries: an authentication that is already there means the whole
 * round trip answers nothing new, so the browser goes straight to the app.
 *
 * <p>The session attribute is what the authentication that finished earlier
 * left behind; the thread's own context is not populated yet at this point
 * in the chain, and on a request that only carries a session cookie it never
 * carries the answer this needs.
 */
final class SignedInSignInRedirectFilter extends OncePerRequestFilter {

    private final String webOrigin;

    SignedInSignInRedirectFilter(String webOrigin) {
        this.webOrigin = webOrigin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/oauth2/authorization/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (alreadySignedIn(request)) {
            response.sendRedirect(webOrigin + "/");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether this request already rides a signed-in session. */
    private static boolean alreadySignedIn(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(stored instanceof SecurityContext context)) {
            return false;
        }
        Authentication authentication = context.getAuthentication();
        return authentication instanceof OAuth2AuthenticationToken && authentication.isAuthenticated();
    }
}
