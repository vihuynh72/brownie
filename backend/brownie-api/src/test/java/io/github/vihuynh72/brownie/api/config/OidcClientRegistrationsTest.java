package io.github.vihuynh72.brownie.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Google joins the providers a browser may pick only when a deployment has
 * given it both halves of a real credential; an unset or half-set pair is a
 * placeholder, not a provider.
 */
class OidcClientRegistrationsTest {

    private final OidcClientRegistrations config = new OidcClientRegistrations();

    /** The one registration a deployment always has; Google is what this test adds or withholds. */
    private static OAuth2ClientProperties configured() {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        OAuth2ClientProperties.Registration entra = new OAuth2ClientProperties.Registration();
        entra.setClientId("entra-client");
        entra.setClientSecret("entra-secret");
        entra.setAuthorizationGrantType("authorization_code");
        entra.setRedirectUri("{baseUrl}/login/oauth2/code/entra");
        properties.getRegistration().put("entra", entra);
        OAuth2ClientProperties.Provider provider = new OAuth2ClientProperties.Provider();
        provider.setAuthorizationUri("https://provider.example/authorize");
        provider.setTokenUri("https://provider.example/token");
        provider.setJwkSetUri("https://provider.example/jwks");
        provider.setUserInfoUri("https://provider.example/userinfo");
        provider.setUserNameAttribute("sub");
        properties.getProvider().put("entra", provider);
        return properties;
    }

    @Test
    void googleJoinsOnlyWithBothValuesSet() {
        ClientRegistrationRepository repo = config.clientRegistrationRepository(
                configured(), "google-client-id", "google-client-secret");

        ClientRegistration google = repo.findByRegistrationId("google");
        assertThat(google).isNotNull();
        assertThat(google.getClientId()).isEqualTo("google-client-id");
    }

    @Test
    void anUnsetPairKeepsGoogleOut() {
        ClientRegistrationRepository repo = config.clientRegistrationRepository(
                configured(), "", "");

        assertThat(repo.findByRegistrationId("google")).isNull();
    }

    @Test
    void aHalfSetPairKeepsGoogleOut() {
        assertThat(config.clientRegistrationRepository(configured(), "id", "")
                .findByRegistrationId("google")).isNull();
        assertThat(config.clientRegistrationRepository(configured(), "", "secret")
                .findByRegistrationId("google")).isNull();
    }
}
