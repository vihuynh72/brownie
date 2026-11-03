package io.github.vihuynh72.brownie.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * The two shapes a failed sign-in's trip home takes: the provider's reason
 * on the query string, or -- when the session already signed in and the
 * callback is only stale -- a quiet landing on the app.
 */
class SignInFailureHandlerTest {

    private final SignInFailureHandler handler = new SignInFailureHandler("http://localhost:5173");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anOrdinaryFailureStillLandsOnHomeWithItsReason() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "the person said no", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/?signin=failed&reason=access_denied");
    }

    @Test
    void aGoogleCallbackNamesGoogleAsTheProvider() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login/oauth2/code/google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "not a test user", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/?signin=failed&reason=access_denied&provider=google");
    }

    @Test
    void anEntraCallbackNamesEntraAsTheProvider() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login/oauth2/code/entra");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "the person said no", null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/?signin=failed&reason=access_denied&provider=entra");
    }

    @Test
    void aStaleCallbackOnAnAnonymousSessionStillCarriesItsReason() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found", null, null)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/?signin=failed&reason=authorization_request_not_found");
    }

    @Test
    void aStaleCallbackOnASignedInSessionLandsQuietlyOnHome() throws Exception {
        // The real order: the earlier sign-in finished, its authentication was saved into the
        // session, and the thread's own context is already clear when the replayed callback arrives.
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "subject-1"), "sub");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "entra"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY,
                context);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                request, response,
                new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found", null, null)));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/");
    }
}
