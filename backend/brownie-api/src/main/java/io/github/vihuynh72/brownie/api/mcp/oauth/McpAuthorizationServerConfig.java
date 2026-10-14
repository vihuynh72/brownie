package io.github.vihuynh72.brownie.api.mcp.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.vihuynh72.brownie.api.mcp.McpCallerResolver;
import io.github.vihuynh72.brownie.api.mcp.McpSettings;
import io.github.vihuynh72.brownie.api.web.ratelimit.RateLimiter;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The OAuth 2.1 authorization server that mints the tokens {@code /mcp}
 * accepts. It runs on its own chain ahead of every other one so its
 * endpoints under {@code /mcp-auth/} never meet the browser chain's CSRF
 * checks or the client login's {@code /oauth2/**} routes. Endpoints that
 * are never asked for -- introspection, PAR, device authorization and all
 * of OIDC -- simply do not exist, because this configurer only registers
 * the endpoint customizers it is called with.
 *
 * <p>Sessions still happen on this chain -- an anonymous authorize must
 * stash the pending request and come back signed in -- but the context is
 * set to explicit save so the minimized {@code user:<id>} principal this
 * chain works under is never written back over the session's OIDC
 * authentication. The session a person holds when the whole flow
 * completes is the ordinary signed-in one they arrived with.
 */
@Configuration
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
class McpAuthorizationServerConfig {

    private static final String CONSENT_PATH = "/mcp-auth/consent";
    private static final String REGISTER_PATH = "/mcp-auth/oauth2/v1/register";

    @Bean
    @Order(1)
    SecurityFilterChain mcpAuthorizationServerFilterChain(
            HttpSecurity http,
            AuthorizationServerSettings authorizationServerSettings,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            OAuth2AuthorizationConsentService authorizationConsentService,
            OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator,
            UserIdentityRepository userIdentityRepository,
            McpSettings settings,
            ObjectProvider<RateLimiter> rateLimiter)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher consentMatcher = PathPatternRequestMatcher.withDefaults().matcher(CONSENT_PATH);
        McpResourceEnforcement resourceEnforcement = new McpResourceEnforcement(settings);

        http.securityMatcher(new OrRequestMatcher(authorizationServer.getEndpointsMatcher(), consentMatcher))
                .with(authorizationServer, server -> server
                        .authorizationServerSettings(authorizationServerSettings)
                        .registeredClientRepository(registeredClientRepository)
                        .authorizationService(authorizationService)
                        .authorizationConsentService(authorizationConsentService)
                        .tokenGenerator(tokenGenerator)
                        .authorizationServerMetadataEndpoint(metadata -> metadata
                                .authorizationServerMetadataCustomizer(builder -> builder
                                        // claim() replaces the computed value outright -- the lists
                                        // below are exactly what this server answers, never what the
                                        // configurer happened to build.
                                        .claim("grant_types_supported", List.of("authorization_code", "refresh_token"))
                                        .claim("token_endpoint_auth_methods_supported", List.of(
                                                "none", "client_secret_basic", "client_secret_post"))
                                        .claim("revocation_endpoint_auth_methods_supported", List.of(
                                                "none", "client_secret_basic", "client_secret_post"))
                                        .claim("scopes_supported",
                                                List.of(McpScope.READ.token(), McpScope.FILL.token(), McpScope.EXPORT.token()))
                                        .claim("code_challenge_methods_supported", List.of("S256"))
                                        // The default document advertises
                                        // endpoints this chain never wires;
                                        // naming them would send clients to
                                        // paths that 404.
                                        .claims(claims -> {
                                            claims.remove("introspection_endpoint");
                                            claims.remove("introspection_endpoint_auth_methods_supported");
                                            claims.remove("pushed_authorization_request_endpoint");
                                            claims.remove("device_authorization_endpoint");
                                            claims.remove("end_session_endpoint");
                                            claims.remove("check_session_iframe");
                                            claims.remove("frontchannel_logout_supported");
                                            claims.remove("frontchannel_logout_session_supported");
                                            claims.remove("backchannel_logout_supported");
                                            claims.remove("backchannel_logout_session_supported");
                                            claims.remove("userinfo_endpoint");
                                            claims.remove("tls_client_certificate_bound_access_tokens");
                                            claims.remove("dpop_signing_alg_values_supported");
                                        })))
                        .clientAuthentication(client -> client
                                .authenticationConverters(converters ->
                                        converters.add(0, new PublicClientAuthenticationConverter()))
                                .authenticationProviders(providers ->
                                        providers.add(new PublicClientAuthenticationProvider(registeredClientRepository))))
                        .authorizationEndpoint(authorization -> authorization
                                .consentPage(CONSENT_PATH)
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider code) {
                                        // Spring's own checks run first (PKCE S256, redirect URI,
                                        // scope); the resource check is added to them, not instead.
                                        code.setAuthenticationValidator(context -> {
                                            new OAuth2AuthorizationCodeRequestAuthenticationValidator().accept(context);
                                            resourceEnforcement.validateAuthorizeRequest(context);
                                            McpScopeRequirement.validate(context);
                                        });
                                    }
                                })))
                        .tokenEndpoint(token -> token
                                // A converter, not a provider: the provider
                                // list is advisory -- ProviderManager keeps
                                // walking after one of them throws, so a
                                // veto there only works if every later
                                // provider also fails. A converter's
                                // exception is the request's whole answer.
                                .accessTokenRequestConverters(converters ->
                                        converters.add(0, resourceEnforcement.tokenRequestConverter())))
                        .clientRegistrationEndpoint(registration -> registration
                                .openRegistrationAllowed(true)
                                .authenticationProviders(providers -> providers.forEach(provider -> {
                                    if (provider instanceof OAuth2ClientRegistrationAuthenticationProvider dcr) {
                                        dcr.setAuthenticationValidator(McpClientRegistration::validate);
                                        dcr.setRegisteredClientConverter(McpClientRegistration::convert);
                                        dcr.setPasswordEncoder(clientSecretEncoder());
                                    }
                                }))))
                .authorizeHttpRequests(authorize -> authorize
                        // The registration endpoint filter is placed after
                        // AuthorizationFilter, so anonymous DCR needs this
                        // rule or it dies at the authorization check before
                        // the filter that would accept it ever runs.
                        .requestMatchers(REGISTER_PATH).permitAll()
                        .anyRequest().authenticated())
                // The endpoints are JSON-RPC and form POSTs between servers;
                // the one browser POST -- consent -- is protected by the
                // authorization server's own state, which a cross-site form
                // cannot mint, so CSRF has nothing left to defend here.
                .csrf(csrf -> csrf.disable())
                .securityContext(context -> context.requireExplicitSave(true))
                // Only the authorize endpoint is browser-facing: an anonymous
                // person there is sent to the app's sign-in and saved back.
                // Every other endpoint is machine-to-machine, so its refusal
                // stays the configurer's own bare 401 -- a machine client
                // must never be handed a login redirect.
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/entra"),
                        PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.GET, "/mcp-auth/oauth2/v1/authorize")));
        rateLimiter.ifAvailable(limiter -> http.addFilterBefore(
                new OAuthEndpointRateLimitFilter(limiter), SecurityContextHolderFilter.class));
        http.addFilterAfter(
                new SessionPrincipalMinimizationFilter(userIdentityRepository), SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean
    AuthorizationServerSettings mcpAuthorizationServerSettings(McpSettings settings) {
        return AuthorizationServerSettings.builder()
                .issuer(settings.publicOrigin())
                .authorizationEndpoint("/mcp-auth/oauth2/v1/authorize")
                .tokenEndpoint("/mcp-auth/oauth2/v1/token")
                .tokenRevocationEndpoint("/mcp-auth/oauth2/v1/revoke")
                .jwkSetEndpoint("/mcp-auth/oauth2/v1/jwks")
                .clientRegistrationEndpoint(REGISTER_PATH)
                .build();
    }

    /**
     * One RSA key, generated at start-up. Every token this server issues
     * is opaque, so nothing signs or verifies a JWT here -- the key exists
     * because the configurer's client-authentication setup asks for a
     * {@code JwtDecoder} bean before it will wire its JWT client-assertion
     * pieces, and the JWK source is what the {@code jwks} endpoint serves.
     * The smallest honest answer to both is one keypair.
     */
    @Bean
    RSAKey mcpJwk() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not build the key the authorization server requires.", e);
        }
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey mcpJwk) {
        return new ImmutableJWKSet<>(new JWKSet(mcpJwk));
    }

    @Bean
    org.springframework.security.oauth2.jwt.JwtDecoder mcpJwtDecoder(RSAKey mcpJwk) {
        try {
            return org.springframework.security.oauth2.jwt.NimbusJwtDecoder
                    .withPublicKey(mcpJwk.toRSAPublicKey()).build();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("Could not build the decoder the authorization server requires.", e);
        }
    }

    /**
     * The generators for the token endpoint's products: opaque access
     * tokens and refresh tokens that rotate on every use -- including for
     * public clients, which the stock refresh generator refuses outright.
     * (Authorization codes are minted by the authorize endpoint's own
     * provider, not through this bean.)
     */
    @Bean
    OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator() {
        return new DelegatingOAuth2TokenGenerator(
                new OAuth2AccessTokenGenerator(), new PublicClientRefreshTokenGenerator());
    }

    /** The OAuth half of {@code /mcp}'s caller resolution, beside the personal-token resolver. */
    @Bean
    McpCallerResolver oAuthAccessTokenCallerResolver(
            OAuth2AuthorizationService authorizationService, McpGrantService grantService, McpSettings settings) {
        return new OAuthAccessTokenCallerResolver(authorizationService, grantService, settings);
    }

    /**
     * The encoder confidential clients' secrets are hashed with at
     * registration -- the same delegating encoder Spring's own pieces use
     * by default, so its {@code {bcrypt}...} output is what the token
     * endpoint's stock client-secret provider can read back.
     */
    private static PasswordEncoder clientSecretEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
