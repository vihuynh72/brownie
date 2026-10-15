package io.github.vihuynh72.brownie.api.persistence.oauth;

import io.github.vihuynh72.brownie.api.mcp.McpTokenKey;
import io.github.vihuynh72.brownie.api.mcp.oauth.GrantRecordingAuthorizationConsentService;
import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * The JDBC stores the authorization server keeps its clients,
 * authorizations and consents in. They live in the persistence package
 * for the same reason everything else that touches the database does:
 * all SQL in the API is written where the tenant-context discipline is
 * kept. The {@code oauth2_*} tables are deliberately outside that
 * discipline -- an authorization belongs to a principal and a client,
 * not a workspace -- but the code that runs the statements still belongs
 * here. Everything is conditional on the MCP surface being enabled,
 * because without it these tables have no writer at all.
 */
@Configuration
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
class OAuthServerPersistence {

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations) {
        // The JDBC repository starts fine empty -- a placeholder client
        // was only ever a workaround for the in-memory one's seed-or-die
        // constructor.
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcOperations jdbcOperations, RegisteredClientRepository registeredClientRepository, McpTokenKey tokenKey) {
        return new HmacOAuth2AuthorizationService(jdbcOperations, registeredClientRepository, tokenKey);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcOperations jdbcOperations,
            RegisteredClientRepository registeredClientRepository,
            McpGrantService grantService,
            WorkspaceRepository workspaceRepository) {
        return new GrantRecordingAuthorizationConsentService(
                new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository),
                registeredClientRepository, grantService, workspaceRepository);
    }
}
