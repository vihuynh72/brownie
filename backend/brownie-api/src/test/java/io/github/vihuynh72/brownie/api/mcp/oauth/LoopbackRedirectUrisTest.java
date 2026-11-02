package io.github.vihuynh72.brownie.api.mcp.oauth;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoopbackRedirectUrisTest {

    @Test
    void theSamePathOnANewPortMatches() {
        assertThat(matches("http://localhost:53682/callback", "http://localhost:60111/callback")).isTrue();
    }

    @Test
    void aDifferentPathDoesNotMatch() {
        assertThat(matches("http://localhost:53682/callback", "http://localhost:60111/other")).isFalse();
    }

    @Test
    void anHttpsLocalhostIsNotALoopbackHttp() {
        assertThat(matches("https://localhost:53682/callback", "https://localhost:60111/callback")).isFalse();
        assertThat(matches("http://localhost:53682/callback", "https://localhost:60111/callback")).isFalse();
    }

    @Test
    void aHostnameThatOnlyStartsWithLocalhostIsNotLoopback() {
        assertThat(matches("http://localhost:53682/callback", "http://localhost.evil.com:60111/callback")).isFalse();
        assertThat(matches("http://localhost.evil.com/callback", "http://localhost.evil.com/callback")).isFalse();
    }

    @Test
    void aFragmentIsNeverAcceptable() {
        assertThat(matches("http://localhost:53682/callback", "http://localhost:60111/callback#frag")).isFalse();
    }

    @Test
    void aRegisteredLoopbackWithNoPortMatchesAnyPort() {
        assertThat(matches("http://localhost/callback", "http://localhost:5000/callback")).isTrue();
    }

    @Test
    void anIpLoopbackIsLeftForSpringsDefault() {
        assertThat(matches("http://127.0.0.1:53682/callback", "http://127.0.0.1:60111/callback")).isFalse();
    }

    private static boolean matches(String registeredUri, String requestedUri) {
        RegisteredClient client = mock(RegisteredClient.class);
        when(client.getRedirectUris()).thenReturn(Set.of(registeredUri));
        OAuth2AuthorizationCodeRequestAuthenticationToken request = mock(
                OAuth2AuthorizationCodeRequestAuthenticationToken.class);
        when(request.getRedirectUri()).thenReturn(requestedUri);
        OAuth2AuthorizationCodeRequestAuthenticationContext context = mock(
                OAuth2AuthorizationCodeRequestAuthenticationContext.class);
        when(context.getRegisteredClient()).thenReturn(client);
        when(context.getAuthentication()).thenReturn(request);
        return LoopbackRedirectUris.matches(context);
    }
}
