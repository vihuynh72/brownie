package io.github.vihuynh72.brownie.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * The filter is the last word before a new OAuth authorization request is
 * written: a session that already signed in gets sent home instead, and
 * every other request reaches the chain untouched.
 */
class SignedInSignInRedirectFilterTest {

    private final SignedInSignInRedirectFilter filter = new SignedInSignInRedirectFilter("http://localhost:5173");

    private static MockHttpServletRequest authorizationRequest() {
        return new MockHttpServletRequest("GET", "/oauth2/authorization/entra");
    }

    @Test
    void anAnonymousVisitorReachesTheChainAndStartsSigningIn() throws Exception {
        AtomicBoolean passed = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(authorizationRequest(), response,
                (request, servletResponse) -> passed.set(true));

        assertThat(passed.get()).isTrue();
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void aSignedInSessionGoesToTheAppWithoutStartingAnotherAuthorization() throws Exception {
        DefaultOAuth2User user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "subject-1"), "sub");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "entra"));
        MockHttpServletRequest request = authorizationRequest();
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        AtomicBoolean passed = new AtomicBoolean(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (request1, servletResponse) -> passed.set(true));

        assertThat(passed.get()).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/");
    }

    @Test
    void requestsThatAreNotAnAuthorizationStartAreNotFiltered() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/me"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/login/oauth2/code/entra"))).isTrue();
    }
}
