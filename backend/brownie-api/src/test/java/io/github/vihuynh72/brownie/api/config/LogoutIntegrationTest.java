package io.github.vihuynh72.brownie.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the one thing a signed-in page needs from {@code POST /logout}:
 * that a CSRF-checked request asking for JSON really ends the server
 * session and comes back with somewhere to send the browser next, while
 * an ordinary request keeps the redirect behaviour it always had. The
 * session here is a plain servlet session holding a real {@code
 * OAuth2AuthenticationToken}, exactly where Spring Security's own
 * session-backed context repository keeps it; the {@code test} profile
 * has no identity-provider end-session endpoint configured, so the
 * fallback destination is this app's own root, which is what the
 * assertion below pins down. The real provider-side destination is
 * verified live against the configured tenant, not here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class LogoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aJsonLogoutEndsTheSessionAndReturnsWhereToGoNext() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(post("/logout").session(session).with(csrf()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.redirectUrl").value("/"));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aPlainLogoutStillRedirectsAsBefore() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aJsonLogoutWithoutACsrfTokenIsStillRefused() throws Exception {
        MockHttpSession session = authenticatedSession();

        mockMvc.perform(post("/logout").session(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        assertThat(session.isInvalid()).isFalse();
    }

    private static MockHttpSession authenticatedSession() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("logout-test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, "https://issuer-logout-test")
                .claim(IdTokenClaimNames.SUB, "subject-logout-test")
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
