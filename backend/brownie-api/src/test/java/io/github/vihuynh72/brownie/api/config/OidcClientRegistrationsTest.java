package io.github.vihuynh72.brownie.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
                configured(), "google-client-id", "google-client-secret", true, "", "");

        ClientRegistration google = repo.findByRegistrationId("google");
        assertThat(google).isNotNull();
        assertThat(google.getClientId()).isEqualTo("google-client-id");
    }

    @Test
    void anUnsetPairKeepsGoogleOut() {
        ClientRegistrationRepository repo = config.clientRegistrationRepository(
                configured(), "", "", true, "", "");

        assertThat(repo.findByRegistrationId("google")).isNull();
    }

    @Test
    void aHalfSetPairKeepsGoogleOut() {
        assertThat(config.clientRegistrationRepository(configured(), "id", "", true, "", "")
                .findByRegistrationId("google")).isNull();
        assertThat(config.clientRegistrationRepository(configured(), "", "secret", true, "", "")
                .findByRegistrationId("google")).isNull();
    }

    @Test
    void googleOnlyDoesNotMapOrDiscoverAnEntraIssuer() {
        OAuth2ClientProperties properties = configured();
        properties.getRegistration().get("entra").setClientId("");
        properties.getProvider().get("entra").setIssuerUri("http://127.0.0.1:1/must-not-be-called");
        ClientRegistrationRepository repo = config.clientRegistrationRepository(
                properties, "google-client", "google-secret", false, "", "");

        assertThat(repo.findByRegistrationId("entra")).isNull();
        assertThat(repo.findByRegistrationId("google").getScopes()).containsExactlyInAnyOrder("openid", "email", "profile");
        assertThat(properties.getRegistration()).containsKey("entra");
    }

    @Test
    void googleOnlyRefusesMissingOrPartialGoogleCredentials() {
        for (String[] credentials : new String[][] {{"", ""}, {"id", ""}, {"", "secret"}}) {
            assertThatThrownBy(() -> config.clientRegistrationRepository(
                    configured(), credentials[0], credentials[1], false, "", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Google-only sign-in requires");
        }
    }

    @Test
    void enabledEntraStillRequiresRealCredentials() {
        assertThatThrownBy(() -> config.clientRegistrationRepository(
                new OAuth2ClientProperties(), "google-client", "google-secret", true, "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Entra sign-in requires");
    }

    @Test
    void theDefaultEntraRegistrationKeepsItsCallbackAndScopes() {
        OAuth2ClientProperties properties = configured();
        properties.getRegistration().clear();
        ClientRegistration entra = config.clientRegistrationRepository(
                properties, "", "", true, "entra-client", "entra-secret").findByRegistrationId("entra");
        assertThat(entra.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/entra");
        assertThat(entra.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void realProfilesBindAndStartGoogleOnlyWithoutAnyEntraValues() {
        for (String profile : new String[] {"local", "pilot", "production"}) {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(OidcClientRegistrations.class)
                    .withPropertyValues("spring.profiles.active=" + profile,
                            "BROWNIE_ENTRA_SIGN_IN_ENABLED=false",
                            "BROWNIE_GOOGLE_CLIENT_ID=google-client", "BROWNIE_GOOGLE_CLIENT_SECRET=google-secret")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var repo = context.getBean(ClientRegistrationRepository.class);
                        assertThat(repo.findByRegistrationId("entra")).isNull();
                        assertThat(repo.findByRegistrationId("google")).isNotNull();
                    });
        }
    }
}
