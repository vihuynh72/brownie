package io.github.vihuynh72.brownie.api.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * The same OIDC "sign out here, then at the identity provider" flow
 * Spring Security provides, with one addition for the single-page app:
 * a logout request that asks for JSON gets a {@code 200} carrying the
 * provider's end-session URL as {@code redirectUrl} instead of a {@code
 * 302} to it.
 *
 * <p>Why the app cannot simply follow the redirect itself: the only way
 * a browser page can satisfy this API's CSRF rule on {@code POST /logout}
 * is a {@code fetch} carrying the {@code X-XSRF-TOKEN} header -- a plain
 * HTML form cannot, because the form-parameter path expects a per-page
 * masked token the app never receives. And a {@code fetch} that is
 * answered with a cross-origin {@code 302} cannot read where it was sent,
 * so the page could never complete the provider-side sign-out. Handing
 * the URL back in the body lets the page finish the flow with an ordinary
 * top-level navigation. The server session is already invalidated by the
 * time this handler runs, whichever branch it takes; the JSON branch only
 * changes how the last step is communicated, never whether it happens.
 */
final class JsonAwareOidcLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

    private final ObjectMapper objectMapper;

    JsonAwareOidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository, ObjectMapper objectMapper) {
        super(clientRegistrationRepository);
        this.objectMapper = objectMapper;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        if (!prefersJson(request)) {
            super.onLogoutSuccess(request, response, authentication);
            return;
        }
        String redirectUrl = determineTargetUrl(request, response, authentication);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("redirectUrl", redirectUrl)));
        response.getWriter().flush();
    }

    private static boolean prefersJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(MediaType.APPLICATION_JSON_VALUE);
    }
}
