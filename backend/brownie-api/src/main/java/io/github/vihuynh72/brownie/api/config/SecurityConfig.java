package io.github.vihuynh72.brownie.api.config;

import io.github.vihuynh72.brownie.api.identity.oidc.BrownieOidcUserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

/**
 * The browser and session boundary described in the master plan (§5.4,
 * §14.5): an opaque server session cookie for the browser, never a
 * provider token; CSRF checked on every mutation; and login/logout wired
 * to the {@code entra} registration whose callback is registered exactly
 * as {@code /login/oauth2/code/entra} (§18.6).
 *
 * <p>{@code /api/v1/me} is the only route that requires authentication so
 * far -- it exists to prove the login flow end to end. Everything else
 * stays open because no workspace or capability model exists yet to check
 * against; P04-03 is where real per-resource authorization replaces this.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BrownieOidcUserService oidcUserService,
            ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {
        http.authorizeHttpRequests(authorize ->
                        authorize.requestMatchers("/api/v1/me").authenticated().anyRequest().permitAll())
                .csrf(csrf -> csrf.spa())
                .oauth2Login(
                        oauth2 -> oauth2.userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository)))
                // A JSON API has no login page to redirect a browser to; an
                // unauthenticated request to a protected route gets a plain 401
                // so the (future) SPA can decide what to show, rather than
                // silently following a redirect to the identity provider.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}");
        return handler;
    }
}
