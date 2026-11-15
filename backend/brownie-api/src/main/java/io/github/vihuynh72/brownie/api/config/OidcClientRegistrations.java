package io.github.vihuynh72.brownie.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.ArrayList;
import java.util.Set;

/**
 * Which identity providers a browser may sign in through. The providers
 * configured in {@code application.yml} are honoured as-is; Entra can be disabled
 * before issuer discovery for a Google-only deployment. {@code google}
 * joins them only when a deployment has given it both halves of a real
 * credential, because the properties binding insists a named registration
 * have a client id -- an empty placeholder cannot sit in configuration,
 * so Google's own registration is built here instead.
 */
@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
class OidcClientRegistrations {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            OAuth2ClientProperties properties,
            @Value("${BROWNIE_GOOGLE_CLIENT_ID:}") String googleClientId,
            @Value("${BROWNIE_GOOGLE_CLIENT_SECRET:}") String googleClientSecret,
            @Value("${BROWNIE_ENTRA_SIGN_IN_ENABLED:true}") boolean entraEnabled,
            @Value("${BROWNIE_OIDC_CLIENT_ID:}") String entraClientId,
            @Value("${BROWNIE_OIDC_CLIENT_SECRET:}") String entraClientSecret) {
        OAuth2ClientProperties selected = new OAuth2ClientProperties();
        selected.getProvider().putAll(properties.getProvider());
        selected.getRegistration().putAll(properties.getRegistration());
        if (entraEnabled) {
            selected.getRegistration().putIfAbsent("entra", entra(entraClientId, entraClientSecret));
            var entra = selected.getRegistration().get("entra");
            if (!usable(entra.getClientId()) || !usable(entra.getClientSecret())) {
                throw new IllegalStateException("Entra sign-in requires BROWNIE_OIDC_CLIENT_ID and BROWNIE_OIDC_CLIENT_SECRET.");
            }
            String providerId = usable(entra.getProvider()) ? entra.getProvider() : "entra";
            var provider = selected.getProvider().get(providerId);
            if (provider == null || (!usable(provider.getIssuerUri()) && !usable(provider.getAuthorizationUri()))) {
                throw new IllegalStateException("Entra sign-in requires BROWNIE_OIDC_ISSUER.");
            }
        } else {
            selected.getRegistration().remove("entra");
            if (!usable(googleClientId) || !usable(googleClientSecret)) {
                throw new IllegalStateException("Google-only sign-in requires BROWNIE_GOOGLE_CLIENT_ID and BROWNIE_GOOGLE_CLIENT_SECRET.");
            }
        }
        // Filter before mapping: mapping an issuer performs discovery, even for an unused provider.
        var registrations = new ArrayList<>(new OAuth2ClientPropertiesMapper(selected).asClientRegistrations().values());
        if (usable(googleClientId) && usable(googleClientSecret)) {
            registrations.add(google(googleClientId, googleClientSecret));
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }

    private static OAuth2ClientProperties.Registration entra(String clientId, String clientSecret) {
        var registration = new OAuth2ClientProperties.Registration();
        registration.setClientId(clientId);
        registration.setClientSecret(clientSecret);
        registration.setAuthorizationGrantType("authorization_code");
        registration.setScope(Set.of("openid", "profile", "email"));
        registration.setRedirectUri("{baseUrl}/login/oauth2/code/entra");
        return registration;
    }

    /**
     * Google's endpoints are spelled out rather than discovered through its
     * issuer URI, which would make every start-up fetch the discovery
     * document and keep test and offline profiles from starting at all.
     */
    private static ClientRegistration google(String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientName("Google")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/google")
                .scope(Set.of("openid", "email", "profile"))
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .issuerUri("https://accounts.google.com")
                .build();
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank();
    }
}
