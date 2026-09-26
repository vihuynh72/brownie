package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import jakarta.servlet.http.HttpSession;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A consent someone has started and not finished: what Google must send back
 * ({@code state}), the secret only this server knows ({@code codeVerifier}),
 * and who asked, for which workspace and kind of access, and where to return
 * them. It lives in the person's own session, under a key of its own, so it
 * can be completed only by the browser that started it and cannot collide
 * with a sign-in going on in another tab. It is taken out of the session
 * before it is checked, so it can be used once at most.
 *
 * <p>{@code pick} says whether the person is choosing Drive files with
 * Google's picker rather than connecting an account. It was added after
 * consents were already being kept in sessions, and it is a plain boolean so
 * that both directions keep working: one stored before it existed reads back
 * as {@code false}, a connection, which is what every consent then was; and
 * a build from before it simply ignores it, where a type of Brownie's own
 * would make that build unable to read the session at all.
 */
record PendingConsent(
        String state,
        String codeVerifier,
        long workspaceId,
        long userId,
        ConnectorAccess access,
        String returnTo,
        long createdAtEpochSecond,
        boolean pick) implements Serializable {

    static final String SESSION_ATTRIBUTE = "brownie.connector.pendingConsent";

    /** Long enough to read a consent screen and choose; short enough that a stale one is not left lying in a session. */
    static final Duration LIFETIME = Duration.ofMinutes(10);

    /** The two pages that start a consent. The value is written into a redirect, so nothing else is ever allowed. */
    private static final Pattern RETURN_TO = Pattern.compile("^/(?:connections|documents/[1-9][0-9]{0,17})$");

    static boolean isAllowedReturnTo(String returnTo) {
        return returnTo != null && RETURN_TO.matcher(returnTo).matches();
    }

    boolean hasExpiredAt(Instant now) {
        return now.getEpochSecond() - createdAtEpochSecond > LIFETIME.toSeconds();
    }

    void storeIn(HttpSession session) {
        session.setAttribute(SESSION_ATTRIBUTE, this);
    }

    /** Removes and returns what is pending in this session; null when nothing is, or what is there is not one of these. */
    static PendingConsent takeFrom(HttpSession session) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        session.removeAttribute(SESSION_ATTRIBUTE);
        return value instanceof PendingConsent pending ? pending : null;
    }

    /** Neither secret. */
    @Override
    public String toString() {
        return "PendingConsent[workspaceId=" + workspaceId + ", access=" + access + ", returnTo=" + returnTo + ", pick=" + pick + "]";
    }
}
