package io.github.vihuynh72.brownie.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * The session's "where were they" note belongs to real browser
 * navigations: a fetch the SPA makes never writes it, a stale API one
 * left over in a session never reads it, and the MCP authorize URL --
 * itself a navigation -- still saves and replays.
 */
class NavigationsOnlyRequestCacheTest {

    private final NavigationsOnlyRequestCache cache = new NavigationsOnlyRequestCache();

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServerName("localhost");
        return request;
    }

    @Test
    void aFetchOfTheSessionProbeIsNotSaved() {
        MockHttpServletRequest request = request("GET", "/api/v1/me");
        request.addHeader("Sec-Fetch-Mode", "cors");
        request.getSession(true);

        cache.saveRequest(request, new MockHttpServletResponse());

        assertThat(request.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNull();
    }

    @Test
    void anXhrToAWebPathIsNotSavedEither() {
        MockHttpServletRequest request = request("GET", "/documents/3");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        request.getSession(true);

        cache.saveRequest(request, new MockHttpServletResponse());

        assertThat(request.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNull();
    }

    @Test
    void aNavigationToAWebPathIsSaved() {
        MockHttpServletRequest request = request("GET", "/documents/3");
        request.addHeader("Sec-Fetch-Mode", "navigate");
        request.getSession(true);

        cache.saveRequest(request, new MockHttpServletResponse());

        Object saved = request.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(saved).isNotNull();
        assertThat(((SavedRequest) saved).getRedirectUrl()).startsWith("http://localhost/documents/3");
    }

    @Test
    void theSuccessHandlerSendsAStaleSavedApiRequestToTheApp() throws Exception {
        MockHttpServletRequest stale = request("GET", "/api/v1/me");
        stale.getSession(true);
        new HttpSessionRequestCache().saveRequest(stale, new MockHttpServletResponse());

        MockHttpServletRequest callback = request("GET", "/login/oauth2/code/entra");
        callback.setSession(stale.getSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        SavedRequestAwareAuthenticationSuccessHandler handler = successHandler();
        handler.onAuthenticationSuccess(callback, response, new TestingAuthenticationToken("u", "p"));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:5173/");
        assertThat(stale.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNull();
    }

    @Test
    void theMcpAuthorizeUrlIsSavedAndReplayedAfterSignIn() throws Exception {
        MockHttpServletRequest authorize = request("GET",
                "/mcp-auth/oauth2/v1/authorize?response_type=code&client_id=c&state=s");
        authorize.addHeader("Sec-Fetch-Mode", "navigate");
        authorize.getSession(true);

        cache.saveRequest(authorize, new MockHttpServletResponse());
        assertThat(authorize.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST")).isNotNull();

        MockHttpServletRequest callback = request("GET", "/login/oauth2/code/entra");
        callback.setSession(authorize.getSession());
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler().onAuthenticationSuccess(callback, response, new TestingAuthenticationToken("u", "p"));

        assertThat(response.getRedirectedUrl())
                .contains("/mcp-auth/oauth2/v1/authorize?response_type=code&client_id=c&state=s");
    }

    private SavedRequestAwareAuthenticationSuccessHandler successHandler() {
        SavedRequestAwareAuthenticationSuccessHandler handler =
                new SavedRequestAwareAuthenticationSuccessHandler();
        handler.setDefaultTargetUrl("http://localhost:5173/");
        handler.setRequestCache(cache);
        return handler;
    }
}
