package io.github.vihuynh72.brownie.api.config;

import io.github.vihuynh72.brownie.api.identity.oidc.BrownieOidcUserService;
import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimitFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * The browser and session boundary: an opaque server session cookie for
 * the browser, never a provider token; CSRF checked on every mutation; and
 * login/logout wired to the {@code entra} registration whose callback is
 * registered exactly as {@code /login/oauth2/code/entra}.
 *
 * <p>Every route requires a session except the few that exist to obtain
 * or end one (login initiation and its callback, logout) and the two that
 * must answer anonymously (the error page, and the e2e session-seeding
 * route, which exists only on the local and test profiles and does its
 * own token and loopback checks). A new controller is therefore protected
 * by default rather than open by omission. Capability checks
 * ({@code WorkspaceAuthorizationService}) and row-level security are in
 * place for the workspace-scoped data; a controller calls into that
 * service rather than repeating access logic of its own. A denied
 * capability check is a plain exception thrown from application code, so
 * {@code ApiExceptionHandler} -- not anything configured here -- is what
 * turns it into a 403; Spring MVC's own dispatch resolves it before it
 * could ever reach a filter-level handler configured on this class.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    /** Scheme, host, and optional port only -- a path or query here would turn the post-sign-in redirect into something a config typo could point anywhere. */
    private static final Pattern ORIGIN_ONLY = Pattern.compile("^https?://[A-Za-z0-9.-]+(?::\\d{1,5})?$");

    /**
     * Where a browser lands after signing in or failing to. In a hosted
     * environment this is the one public origin that serves both the web
     * app and this API; in local development the web app runs on its own
     * Vite port while the identity provider's callback must still arrive
     * here, so the two origins differ and this value names the web app's.
     */
    private final String webOrigin;

    SecurityConfig(@Value("${brownie.web.origin}") String webOrigin) {
        if (webOrigin == null || !ORIGIN_ONLY.matcher(webOrigin).matches()) {
            throw new IllegalStateException(
                    "brownie.web.origin must be an absolute http(s) origin with no path, query, or fragment; got \"" + webOrigin + "\".");
        }
        this.webOrigin = webOrigin;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BrownieOidcUserService oidcUserService,
            ClientRegistrationRepository clientRegistrationRepository,
            ObjectMapper objectMapper,
            ObjectProvider<RateLimitFilter> rateLimitFilter)
            throws Exception {
        // Straight after the session has said who is asking, and before CSRF, sign-in and the rest answer anything.
        rateLimitFilter.ifAvailable(filter -> http.addFilterAfter(filter, SecurityContextHolderFilter.class));
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/oauth2/**", "/login/**", "/logout", "/error", "/test-support/**")
                        .permitAll()
                        // Liveness and readiness are answered without a session so a
                        // load balancer or orchestrator can ask; health shows no
                        // details (management.endpoint.health.show-details: never)
                        // and is the only management endpoint exposed.
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf.spa())
                // Success and failure both land on the web app's own origin: the
                // provider's callback arrives at this API, which in local
                // development is not where the pages live. See SignInFailureHandler
                // for why a failure needs a real destination at all.
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .defaultSuccessUrl(webOrigin + "/")
                        .failureHandler(new SignInFailureHandler(webOrigin)))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository, objectMapper)))
                // A JSON API has no login page to redirect a browser to; an
                // unauthenticated request to a protected route gets a plain 401
                // so the (future) SPA can decide what to show, rather than
                // silently following a redirect to the identity provider.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /**
     * Only ever reached by a CSRF-checked {@code POST /logout}; the
     * session is already invalidated by the time it runs. A plain request
     * is redirected on to the identity provider's own end-session page
     * and back to this app's origin; a request asking for JSON gets that
     * same destination as a body instead -- see {@link
     * JsonAwareOidcLogoutSuccessHandler} for why the page needs it that way.
     */
    private LogoutSuccessHandler oidcLogoutSuccessHandler(
            ClientRegistrationRepository clientRegistrationRepository, ObjectMapper objectMapper) {
        JsonAwareOidcLogoutSuccessHandler handler =
                new JsonAwareOidcLogoutSuccessHandler(clientRegistrationRepository, objectMapper);
        handler.setPostLogoutRedirectUri("{baseUrl}");
        return handler;
    }
}
