package io.github.vihuynh72.brownie.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

@SpringBootTest(properties = {
        "BROWNIE_ENTRA_SIGN_IN_ENABLED=false",
        "BROWNIE_GOOGLE_CLIENT_ID=google-only-test-client",
        "BROWNIE_GOOGLE_CLIENT_SECRET=google-only-test-secret"
})
@AutoConfigureMockMvc
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
class GoogleOnlySignInIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository registeredClients;

    @Test
    void welcomeOffersOnlyGoogle() throws Exception {
        mockMvc.perform(get("/api/v1/welcome"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInProviders.length()").value(1))
                .andExpect(jsonPath("$.signInProviders[0]").value("google"));
    }

    @Test
    void theRealSecurityChainStartsGoogleSignIn() throws Exception {
        var result = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=google-only-test-client")
                .contains("redirect_uri=http://localhost/login/oauth2/code/google");
    }
    @Test
    void anonymousMcpAuthorizationStartsGoogleAndPreservesTheRequest() throws Exception {
        var client = org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId("test-id")
                .clientId("test-client")
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:19999/cb").scope("read").build();
        org.mockito.Mockito.when(registeredClients.findByClientId("test-client")).thenReturn(client);
        String authorize = "/mcp-auth/oauth2/v1/authorize?response_type=code&client_id=test-client"
                + "&redirect_uri=http://localhost:19999/cb&scope=read&state=test-state"
                + "&resource=http://localhost:8081/mcp&code_challenge=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO"
                + "&code_challenge_method=S256";
        var result = mockMvc.perform(get(authorize))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/google"))
                .andReturn();
        var session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        var saved = (org.springframework.security.web.savedrequest.SavedRequest)
                session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(saved.getRedirectUrl()).contains("client_id=test-client", "state=test-state");
        var google = mockMvc.perform(get("/oauth2/authorization/google").session(
                        (org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(google.getResponse().getRedirectedUrl())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("redirect_uri=http://localhost/login/oauth2/code/google");
    }

}
