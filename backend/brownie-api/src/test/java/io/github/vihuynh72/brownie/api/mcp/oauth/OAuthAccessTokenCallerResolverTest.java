package io.github.vihuynh72.brownie.api.mcp.oauth;

import io.github.vihuynh72.brownie.api.mcp.McpSettings;
import io.github.vihuynh72.brownie.core.mcp.McpCaller;
import io.github.vihuynh72.brownie.core.mcp.McpGrantKind;
import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.mcp.ResolvedMcpGrant;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every door an access token must pass before it becomes a caller. The
 * resource check is the load-bearing one: a token minted for anything
 * other than this deployment's one configured resource is refused here
 * even when every other check would pass.
 */
class OAuthAccessTokenCallerResolverTest {

    private static final String RESOURCE = "http://localhost:8081/mcp";

    private final OAuth2AuthorizationService authorizationService = mock(OAuth2AuthorizationService.class);
    private final McpGrantService grantService = mock(McpGrantService.class);
    private final OAuthAccessTokenCallerResolver resolver = new OAuthAccessTokenCallerResolver(
            authorizationService, grantService, new McpSettings("http://localhost:8081"));

    @Test
    void anActiveTokenForTheConfiguredResourceResolvesToTheGrant() {
        RegisteredClient client = client();
        OAuth2Authorization authorization = authorization(client, "user:7", RESOURCE, Set.of("read", "fill"));
        when(authorizationService.findByToken("raw-token", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization);
        when(grantService.findLiveOAuthGrant(7, client.getId()))
                .thenReturn(Optional.of(new ResolvedMcpGrant(3, 44, 7, McpGrantKind.OAUTH_CLIENT, "chat",
                        Set.of(McpScope.READ))));

        Optional<McpCaller> resolved = resolver.resolve("raw-token");

        // The grant narrows what the token asked for: read only.
        assertThat(resolved).isPresent();
        assertThat(resolved.get().userId()).isEqualTo(7);
        assertThat(resolved.get().workspaceId()).isEqualTo(3);
        assertThat(resolved.get().grantId()).isEqualTo(44);
        assertThat(resolved.get().scopes()).containsExactly(McpScope.READ);
    }

    @Test
    void aTokenIssuedForAnotherResourceIsRefusedEvenWhenEverythingElseHolds() {
        RegisteredClient client = client();
        OAuth2Authorization authorization = authorization(client, "user:7", "https://other.example/mcp", Set.of("read"));
        when(authorizationService.findByToken("foreign", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization);

        assertThat(resolver.resolve("foreign")).isEmpty();
    }

    @Test
    void anExpiredTokenIsRefused() {
        OAuth2AccessToken expired = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "stale",
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client())
                .id("a").principalName("user:7").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request(RESOURCE))
                .token(expired)
                .build();
        when(authorizationService.findByToken("stale", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization);

        assertThat(resolver.resolve("stale")).isEmpty();
    }

    @Test
    void aPrincipalThatIsNotAMinimizedUserIsRefused() {
        OAuth2Authorization authorization = authorization(client(), "someone@example.com", RESOURCE, Set.of("read"));
        when(authorizationService.findByToken("raw", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization);

        assertThat(resolver.resolve("raw")).isEmpty();
    }

    @Test
    void aTokenWhoseGrantIsGoneIsRefused() {
        RegisteredClient client = client();
        OAuth2Authorization authorization = authorization(client, "user:7", RESOURCE, Set.of("read"));
        when(authorizationService.findByToken("raw", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(authorization);
        when(grantService.findLiveOAuthGrant(7, client.getId())).thenReturn(Optional.empty());

        assertThat(resolver.resolve("raw")).isEmpty();
    }

    @Test
    void anUnknownTokenResolvesNothing() {
        when(authorizationService.findByToken(eq("nope"), isNull())).thenReturn(null);
        when(authorizationService.findByToken("nope", OAuth2TokenType.ACCESS_TOKEN)).thenReturn(null);

        assertThat(resolver.resolve("nope")).isEmpty();
    }

    private static RegisteredClient client() {
        return RegisteredClient.withId("rc-1")
                .clientId("mcp-client-1")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:19999/cb")
                .build();
    }

    private static OAuth2AuthorizationRequest request(String resource) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("http://localhost:8081/mcp-auth/oauth2/v1/authorize")
                .clientId("mcp-client-1")
                .redirectUri("http://localhost:19999/cb")
                .additionalParameters(parameters -> parameters.put("resource", resource))
                .build();
    }

    private static OAuth2Authorization authorization(
            RegisteredClient client, String principal, String resource, Set<String> scopes) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "live",
                Instant.now(), Instant.now().plusSeconds(3600), scopes);
        return OAuth2Authorization.withRegisteredClient(client)
                .id("a").principalName(principal).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request(resource))
                .token(accessToken)
                .build();
    }
}
