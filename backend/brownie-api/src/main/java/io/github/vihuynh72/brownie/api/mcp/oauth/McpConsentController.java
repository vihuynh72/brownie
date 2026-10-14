package io.github.vihuynh72.brownie.api.mcp.oauth;

import java.net.URI;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The consent screen {@code consentPage} points at. It is deliberately a
 * page the API itself renders: the only dynamic values -- the client's
 * name, the host its redirect URI sends codes to, and the scopes it asked
 * for -- are all escaped before they touch the HTML, and the form posts
 * straight back to the authorize endpoint.
 *
 * <p>There is no CSRF token on the form on purpose: the {@code state}
 * hidden field is the authorization server's own consent state, minted
 * for exactly this pending authorization and verified against the
 * signed-in principal when the POST arrives, so a cross-site form cannot
 * mint a usable one. Allow submits the consented scopes; Deny submits the
 * same client_id and state with none, which the authorization server
 * answers as {@code access_denied}.
 */
@Controller
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
final class McpConsentController {

    private final RegisteredClientRepository registeredClientRepository;
    private final AuthorizationServerSettings authorizationServerSettings;

    McpConsentController(
            RegisteredClientRepository registeredClientRepository,
            AuthorizationServerSettings authorizationServerSettings) {
        this.registeredClientRepository = registeredClientRepository;
        this.authorizationServerSettings = authorizationServerSettings;
    }

    @GetMapping(value = "/mcp-auth/consent", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    ResponseEntity<String> consent(
            @RequestParam("client_id") String clientId,
            @RequestParam("state") String state,
            @RequestParam(value = "scope", required = false) List<String> scopes) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Unknown client.");
        }
        List<String> requested = scopes == null ? List.of() : scopes;
        StringBuilder scopeRows = new StringBuilder();
        StringBuilder scopeFields = new StringBuilder();
        for (String scope : requested) {
            scopeRows.append("<li><strong>").append(escape(scope)).append("</strong> &mdash; ")
                    .append(scopeWords(scope)).append("</li>");
            scopeFields.append("<input type=\"hidden\" name=\"scope\" value=\"").append(escape(scope)).append("\">");
        }
        String page = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Connect to Brownie</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                body { font-family: system-ui, sans-serif; background: #f4f1ec; color: #2d2a26;
                       display: flex; justify-content: center; padding: 3rem 1rem; }
                main { background: #fff; border-radius: 12px; padding: 2rem; max-width: 30rem;
                       box-shadow: 0 1px 3px rgba(0,0,0,.12); }
                h1 { font-size: 1.3rem; margin-top: 0; }
                ul { padding-left: 1.2rem; }
                .actions { display: flex; gap: .75rem; margin-top: 1.5rem; }
                button { font: inherit; padding: .6rem 1.4rem; border-radius: 8px; cursor: pointer; }
                .allow { background: #4a6741; color: #fff; border: none; }
                .deny { background: transparent; border: 1px solid #b8b0a4; color: #5c564c; }
                </style></head><body><main>
                <h1>Connect %s to Brownie</h1>
                <p><strong>%s</strong> (it sends you back to <code>%s</code>) asks to use Brownie in
                your workspace with these permissions:</p>
                <ul>%s</ul>
                <p>Brownie never fills or exports a document without your confirmation in the chat.</p>
                <div class="actions">
                <form method="post" action="%s" style="display:inline">
                <input type="hidden" name="client_id" value="%s">
                <input type="hidden" name="state" value="%s">
                %s
                <button type="submit" class="allow">Allow</button>
                </form>
                <form method="post" action="%s" style="display:inline">
                <input type="hidden" name="client_id" value="%s">
                <input type="hidden" name="state" value="%s">
                <button type="submit" class="deny">Deny</button>
                </form>
                </div></main></body></html>
                """.formatted(
                escape(client.getClientName()),
                escape(client.getClientName()),
                escape(redirectHost(client)),
                scopeRows,
                escape(authorizationServerSettings.getAuthorizationEndpoint()),
                escape(clientId),
                escape(state),
                scopeFields,
                escape(authorizationServerSettings.getAuthorizationEndpoint()),
                escape(clientId),
                escape(state));
        return ResponseEntity.ok(page);
    }

    /** The host the client's first redirect URI points at -- where an Allow sends the code. */
    private static String redirectHost(RegisteredClient client) {
        String redirectUri = client.getRedirectUris().stream().findFirst().orElse("");
        try {
            String host = URI.create(redirectUri).getHost();
            return host == null ? redirectUri : host;
        } catch (IllegalArgumentException e) {
            return redirectUri;
        }
    }

    private static String scopeWords(String scope) {
        return switch (scope) {
            case "read" -> "see your templates and documents";
            case "fill" -> "start documents, add text sources, and propose fills you confirm";
            case "export" -> "export documents and hand you a one-time download link";
            default -> "a permission Brownie does not describe";
        };
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
