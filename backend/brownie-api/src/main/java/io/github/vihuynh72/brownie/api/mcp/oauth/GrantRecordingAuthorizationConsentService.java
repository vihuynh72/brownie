package io.github.vihuynh72.brownie.api.mcp.oauth;

import io.github.vihuynh72.brownie.core.mcp.McpGrantService;
import io.github.vihuynh72.brownie.core.mcp.McpScope;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import java.util.EnumSet;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * JDBC consent storage with the Brownie side-effect of a consent: every
 * {@code save} is a person pressing Allow, so beside the consent row the
 * call records the {@code OAUTH_CLIENT} grant the {@code /mcp} chain
 * later resolves an access token against. The consent's principal name is
 * already the minimized {@code user:<id>}, which is what ties the two
 * stores together. {@code remove} only delegates -- a Deny never reaches
 * {@code save}, and a replaced consent is re-saved before the old row
 * goes, so the grant simply refreshes on the next Allow.
 */
public final class GrantRecordingAuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private final OAuth2AuthorizationConsentService delegate;
    private final RegisteredClientRepository registeredClientRepository;
    private final McpGrantService grantService;
    private final WorkspaceRepository workspaceRepository;

    public GrantRecordingAuthorizationConsentService(
            OAuth2AuthorizationConsentService delegate,
            RegisteredClientRepository registeredClientRepository,
            McpGrantService grantService,
            WorkspaceRepository workspaceRepository) {
        this.delegate = delegate;
        this.registeredClientRepository = registeredClientRepository;
        this.grantService = grantService;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        delegate.save(authorizationConsent);
        OptionalLong userId = McpPrincipalName.userIdOf(authorizationConsent.getPrincipalName());
        Set<McpScope> scopes = consentScopes(authorizationConsent);
        var client = registeredClientRepository.findById(authorizationConsent.getRegisteredClientId());
        if (userId.isPresent() && client != null && !scopes.isEmpty()) {
            long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId.getAsLong()).id();
            grantService.grantOAuthAccess(
                    workspaceId, userId.getAsLong(), client.getClientName(),
                    authorizationConsent.getRegisteredClientId(), scopes);
        }
    }

    @Override
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        delegate.remove(authorizationConsent);
    }

    @Override
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return delegate.findById(registeredClientId, principalName);
    }

    private static Set<McpScope> consentScopes(OAuth2AuthorizationConsent consent) {
        Set<McpScope> scopes = EnumSet.noneOf(McpScope.class);
        for (String scope : consent.getScopes()) {
            McpScope.parse(scope).ifPresent(scopes::add);
        }
        return scopes;
    }
}
