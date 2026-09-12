package io.github.vihuynh72.brownie.api.config;

import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that removing a session from the store it actually lives in is
 * enough on its own to fail closed, whatever removed it -- not only a
 * self-service logout, already proven elsewhere, but any other way a
 * session stops being valid (an operator's own action, an expiry sweep,
 * and so on). The test profile turns the JDBC-backed session store off for
 * every other test (see application.yml, since most of them run against a
 * disposable H2 database with no spring_session table); this is the one
 * test that genuinely needs that store active against a real Postgres, so
 * it overrides the exclusion back off for itself alone.
 *
 * <p>The session here is built directly through the same repository bean
 * the application uses at runtime, storing an authenticated {@code
 * SecurityContext} exactly where {@code HttpSessionSecurityContextRepository}
 * would, rather than through a real OIDC login. Spring Security Test's own
 * session-priming helpers (such as {@code oidcLogin()}) turned out not to
 * fit here: they authenticate a request by writing the context onto a
 * throwaway servlet session created before Spring Session's filter ever
 * runs, so nothing is ever written to spring_session at all -- confirmed
 * directly by querying the repository right after using one, and finding
 * it empty. That sidesteps the exact mechanism this test exists to check.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@Testcontainers
class SessionRevocationIntegrationTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Test
    void aSessionRemovedFromItsStoreCanNoLongerAuthenticateEvenWithTheOriginalCookieStillPresented() throws Exception {
        String issuer = "https://issuer-session-revocation";
        String subject = "subject-revoked-session";
        userIdentityRepository.recordLogin(issuer, subject, null, null);

        Session session = createAuthenticatedSession(sessionRepository, authenticatedContext(issuer, subject));
        // The default cookie serializer base64-encodes the session ID
        // rather than writing it as plain text; a cookie built from the
        // raw ID alone doesn't resolve to anything, and the request comes
        // back as a fresh, unauthenticated visitor instead.
        Cookie sessionCookie = new Cookie(
                "SESSION", Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/v1/me").cookie(sessionCookie)).andExpect(status().isOk());

        var sessionsForUser = sessionRepository.findByPrincipalName(subject);
        assertThat(sessionsForUser).containsKey(session.getId());
        sessionRepository.deleteById(session.getId());

        mockMvc.perform(get("/api/v1/me").cookie(sessionCookie)).andExpect(status().isUnauthorized());
    }

    private static <S extends Session> S createAuthenticatedSession(
            FindByIndexNameSessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session;
    }

    private SecurityContext authenticatedContext(String issuer, String subject) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-token")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim(IdTokenClaimNames.ISS, issuer)
                .claim(IdTokenClaimNames.SUB, subject)
                .build();
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "entra"));
        return context;
    }
}
