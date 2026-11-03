package io.github.vihuynh72.brownie.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Where a failed sign-in goes. Spring's default sends it to {@code
 * /login?error}, a page this API deliberately never generates (its
 * unauthenticated routes answer 401, not a login form), so every failed
 * sign-in used to end on a JSON 404 that said nothing about why -- and the
 * real reason was only ever kept in a session attribute at trace level.
 *
 * <p>This handler logs the provider's error code and description once, at
 * WARN, then sends the browser back to the web app's own origin with just
 * the code in the query string. The code is passed on only if it is a
 * plain lowercase token (the shape every OAuth error code has), so nothing
 * a provider or a forged callback puts in the URL can reach the page as
 * anything but a short identifier the app renders as text.
 */
final class SignInFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(SignInFailureHandler.class);
    private static final Pattern SAFE_CODE = Pattern.compile("[a-z0-9_]{1,64}");
    private static final String FALLBACK_CODE = "authentication_failed";

    private final String webOrigin;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    SignInFailureHandler(String webOrigin) {
        this.webOrigin = webOrigin;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        String code = FALLBACK_CODE;
        String description = "";
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            OAuth2Error error = oauth2Exception.getError();
            if (error.getErrorCode() != null && SAFE_CODE.matcher(error.getErrorCode()).matches()) {
                code = error.getErrorCode();
            }
            if (error.getDescription() != null) {
                description = error.getDescription();
            }
        }
        log.warn("Sign-in failed: code={} description={}", code, description);
        /* A stale callback against a session that already signed in -- a second tab finishing first,
           or an old callback URL revisited -- is not a failure worth naming on the page. */
        if ("authorization_request_not_found".equals(code) && alreadySignedIn(request)) {
            redirectStrategy.sendRedirect(request, response, webOrigin + "/");
            return;
        }
        String provider = providerOf(request.getRequestURI());
        String target = webOrigin + "/?signin=failed&reason=" + code + (provider != null ? "&provider=" + provider : "");
        redirectStrategy.sendRedirect(request, response, target);
    }

    /**
     * Which provider's callback this was, from the callback's own path --
     * the only place the registration id still lives once the login has
     * failed. Anything that is not a /login/oauth2/code/<id> URI leaves it
     * out rather than guess.
     */
    private static String providerOf(String uri) {
        if (uri != null && uri.startsWith("/login/oauth2/code/")) {
            String id = uri.substring("/login/oauth2/code/".length());
            return SAFE_CODE.matcher(id).matches() ? id : null;
        }
        return null;
    }

    /**
     * Whether this request already rides a signed-in session, as a replayed callback's would. The
     * authentication has to come from the session the callback carries: by the time a failure
     * reaches this handler the thread's own context is already cleared.
     */
    private static boolean alreadySignedIn(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object stored = session.getAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY);
        if (!(stored instanceof org.springframework.security.core.context.SecurityContext context)) {
            return false;
        }
        Authentication authentication = context.getAuthentication();
        return authentication instanceof OAuth2AuthenticationToken && authentication.isAuthenticated();
    }
}
