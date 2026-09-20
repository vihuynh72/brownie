package io.github.vihuynh72.brownie.api.retention;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Ends every server-side session of one signed-in person, on every device,
 * not only the one that made the request. The session store indexes
 * sessions by subject alone, and two identity providers may issue the same
 * subject to different people, so each candidate's own issuer is checked
 * before it is removed.
 *
 * <p>Takes an {@link ObjectProvider} because the fast, database-free test
 * context runs without the JDBC session store; there is then nothing to
 * revoke beyond the request's own session, which the caller ends itself.
 */
@Component
class SessionRevoker {

    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessionRepositoryProvider;

    SessionRevoker(ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessionRepositoryProvider) {
        this.sessionRepositoryProvider = sessionRepositoryProvider;
    }

    int revokeAll(String issuer, String subject) {
        FindByIndexNameSessionRepository<? extends Session> sessionRepository = sessionRepositoryProvider.getIfAvailable();
        if (sessionRepository == null) {
            return 0;
        }
        int revoked = 0;
        Map<String, ? extends Session> candidates = sessionRepository.findByPrincipalName(subject);
        for (Session session : candidates.values()) {
            if (belongsTo(session, issuer, subject)) {
                sessionRepository.deleteById(session.getId());
                revoked++;
            }
        }
        return revoked;
    }

    private static boolean belongsTo(Session session, String issuer, String subject) {
        Object attribute = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(attribute instanceof SecurityContext context)) {
            return false;
        }
        Authentication authentication = context.getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return false;
        }
        return user.getIssuer() != null && issuer.equals(user.getIssuer().toString()) && subject.equals(user.getSubject());
    }
}
