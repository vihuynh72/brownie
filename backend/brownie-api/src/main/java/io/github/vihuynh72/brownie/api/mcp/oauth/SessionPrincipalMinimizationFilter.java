package io.github.vihuynh72.brownie.api.mcp.oauth;

import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Swaps the signed-in browser's OIDC authentication for a minimal one --
 * {@code user:<id>} and nothing else -- for the length of an
 * authorization-server request. Everything the server persists (the
 * authorization's {@code principal_name}, the serialized principal inside
 * its attributes, the consent record) then carries the minimal name
 * rather than the provider's identity.
 *
 * <p>The chain's context is set to explicit save, so the swap lives only
 * as long as the request and the person's session stays their normal
 * OIDC one. An identity the login row cannot find refuses rather than
 * persisting a principal we did not mean to store.
 */
class SessionPrincipalMinimizationFilter extends OncePerRequestFilter {

    private final UserIdentityRepository userIdentityRepository;

    SessionPrincipalMinimizationFilter(UserIdentityRepository userIdentityRepository) {
        this.userIdentityRepository = userIdentityRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof OAuth2AuthenticationToken oauth
                && oauth.getPrincipal() instanceof OidcUser user) {
            long userId = userIdentityRepository
                    .findByIssuerAndSubject(user.getIssuer().toString(), user.getSubject())
                    .orElseThrow(() -> new IllegalStateException(
                            "Signed-in OIDC identity has no Brownie user; refusing to persist it to OAuth storage."))
                    .id();
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(McpPrincipalName.of(userId), null, List.of()));
        }
        chain.doFilter(request, response);
    }
}
