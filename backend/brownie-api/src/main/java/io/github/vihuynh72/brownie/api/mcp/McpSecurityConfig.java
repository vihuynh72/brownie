package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The stateless bearer boundary in front of {@code /mcp}: no session, no
 * CSRF (there is no browser and nothing a cross-site form could do with a
 * JSON-RPC endpoint anyway), and every failure answered as RFC 6750/9728
 * wants. Ordered before the browser chain, so the two never fight over a
 * request: {@code /mcp} and the protected-resource document come here
 * first, everything else falls through to sessions.
 *
 * <p>Authentication itself is a resolver per credential kind -- a token
 * resolves to an {@link McpCaller} or the chain answers 401 with the
 * {@code resource_metadata} challenge naming this deployment's fixed
 * public origin. The scope filter sits after authentication and answers
 * the 403 an under-scoped call earns. The rate filter counts per grant,
 * which is only possible after the token has been resolved, so it is
 * anchored after the bearer filter rather than at the session point the
 * browser chain uses.
 */
@Configuration
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
class McpSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain mcpSecurityFilterChain(
            HttpSecurity http,
            List<McpCallerResolver> resolvers,
            McpSettings settings,
            tools.jackson.databind.ObjectMapper objectMapper,
            io.github.vihuynh72.brownie.core.mcp.McpToolCallRepository toolCallRepository,
            ObjectProvider<io.github.vihuynh72.brownie.api.web.ratelimit.RateLimiter> rateLimiter)
            throws Exception {
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        // The stock resolver names the bare well-known path; RFC 9728 has it
        // carry the resource path, and the whole address is this deployment's
        // configured origin so a forged Host cannot rewrite it.
        entryPoint.setResourceMetadataParameterResolver(request -> settings.protectedResourceMetadataUrl());

        http.securityMatcher(
                        "/mcp",
                        "/mcp/**",
                        "/.well-known/oauth-protected-resource",
                        "/.well-known/oauth-protected-resource/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/.well-known/oauth-protected-resource",
                                "/.well-known/oauth-protected-resource/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                        // A bearer request must never change the person's web session.
                        .sessionFixation(fixation -> fixation.none()))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(entryPoint)
                        .authenticationManagerResolver(request -> bearer -> authenticate(bearer, resolvers))
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(builder -> builder
                                .resource(settings.resource())
                                .authorizationServer(settings.publicOrigin())
                                // The default document claims
                                // certificate-bound tokens are accepted;
                                // nothing on this chain ever checks a
                                // client certificate, so the claim goes.
                                .claims(claims -> claims.remove("tls_client_certificate_bound_access_tokens"))
                                .scopes(scopes -> {
                                    scopes.add(McpScope.READ.token());
                                    scopes.add(McpScope.FILL.token());
                                    scopes.add(McpScope.EXPORT.token());
                                }))))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
        // The bearer filter authenticates; only after that is the caller's
        // grant known, which is the identity the MCP rate class counts.
        // Registered first of the two custom filters anchored on it, so it
        // bounds the call before the scope check runs.
        rateLimiter.ifAvailable(limiter -> http.addFilterAfter(
                new McpRateLimitFilter(limiter), BearerTokenAuthenticationFilter.class));
        http.addFilterAfter(
                new McpScopeEnforcementFilter(objectMapper, toolCallRepository),
                BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private Authentication authenticate(Authentication bearer, List<McpCallerResolver> resolvers) throws AuthenticationException {
        String token = ((BearerTokenAuthenticationToken) bearer).getToken();
        for (McpCallerResolver resolver : resolvers) {
            var caller = resolver.resolve(token);
            if (caller.isPresent()) {
                return new McpAuthentication(caller.get());
            }
        }
        throw new BadCredentialsException("Unknown or revoked bearer token");
    }
}
