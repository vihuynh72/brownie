package io.github.vihuynh72.brownie.api.identity;

/**
 * The session is genuine, but the person it names has no identity record
 * any more. Deleting a workspace removes that record and ends that
 * person's sessions in the same request, so this is not reachable through
 * the application today; it exists so that any future way of removing an
 * identity that forgets the sessions is answered with "sign in again" and
 * the session's end, not with a server error on every route.
 *
 * <p>It carries no issuer or subject: the message of an exception ends up
 * in logs, and who someone is does not belong there.
 */
public class AuthenticatedIdentityMissingException extends RuntimeException {

    public AuthenticatedIdentityMissingException() {
        super("The signed-in session names a person with no identity record.");
    }
}
