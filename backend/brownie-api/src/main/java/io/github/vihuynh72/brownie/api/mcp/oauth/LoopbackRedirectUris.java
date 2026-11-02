package io.github.vihuynh72.brownie.api.mcp.oauth;

import java.net.URI;

import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;

/**
 * Loopback names are not loopback addresses. Spring Authorization Server
 * already ignores the port on a redirect URI whose host is an IP literal
 * for this machine -- {@code 127.0.0.1} or {@code ::1} -- because a
 * client that can bind a loopback port for one redirect can bind any of
 * them, and asking people to re-authorize for every new ephemeral port
 * is busywork with no security behind it.
 *
 * <p>{@code localhost} is not an IP literal, so it does not get that
 * treatment, and a client like Claude Code that registers
 * {@code http://localhost:53682/callback} and later asks to be sent back
 * at {@code http://localhost:60111/callback} is refused. The same
 * reasoning holds for the name as for the number -- Anthropic's connector
 * guidance says the port on a loopback host is not part of the client's
 * identity -- so it is given here instead, for {@code http://localhost}
 * only: the scheme, host, path and query must match a registered URI
 * exactly, and a fragment is never acceptable.
 */
final class LoopbackRedirectUris {

    private static final String LOCALHOST = "localhost";

    private LoopbackRedirectUris() {
    }

    /**
     * True when the requested redirect URI is the localhost rule above:
     * an {@code http://localhost} URI with no fragment whose client has a
     * registered {@code http://localhost} URI with the same path and
     * query, whatever port each used.
     */
    static boolean matches(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        URI requested = request == null ? null : parse(request.getRedirectUri());
        if (requested == null || !isLoopbackHttp(requested)) {
            return false;
        }
        for (String registeredUri : context.getRegisteredClient().getRedirectUris()) {
            URI registered = parse(registeredUri);
            if (registered != null && isLoopbackHttp(registered)
                    && samePathAndQuery(requested, registered)) {
                return true;
            }
        }
        return false;
    }

    private static URI parse(String uri) {
        // An authorize request may carry no redirect_uri at all -- a client
        // with several registered callbacks then has nothing to match.
        if (uri == null) {
            return null;
        }
        try {
            URI parsed = URI.create(uri);
            return parsed.getFragment() != null ? null : parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isLoopbackHttp(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
                && LOCALHOST.equalsIgnoreCase(uri.getHost());
    }

    private static boolean samePathAndQuery(URI first, URI second) {
        String firstPath = first.getRawPath() == null ? "" : first.getRawPath();
        String secondPath = second.getRawPath() == null ? "" : second.getRawPath();
        if (!firstPath.equals(secondPath)) {
            return false;
        }
        String firstQuery = first.getRawQuery();
        String secondQuery = second.getRawQuery();
        return firstQuery == null ? secondQuery == null : firstQuery.equals(secondQuery);
    }
}
