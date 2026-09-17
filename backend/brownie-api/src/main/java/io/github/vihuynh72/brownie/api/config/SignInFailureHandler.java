package io.github.vihuynh72.brownie.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
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
        redirectStrategy.sendRedirect(request, response, webOrigin + "/?signin=failed&reason=" + code);
    }
}
