package io.github.vihuynh72.brownie.core.identity;

import java.util.Optional;

/**
 * The domain contract for issuer-subject identity mapping. {@code
 * recordLogin} is the only way a {@link UserIdentity} comes into
 * existence: there is no separate registration step, matching an OIDC
 * identity provider that has already done sign-up/sign-in itself.
 */
public interface UserIdentityRepository {

    /**
     * Creates the identity on first login, or updates its contact fields
     * and last-login time on every later one. Keyed by the unique
     * (issuer, subject) pair, never by email.
     */
    UserIdentity recordLogin(String issuer, String subject, String email, String displayName);

    /**
     * The same, plus whether the provider swore to the email: when it did and
     * that email already names exactly one person, the sign-in lands on them
     * and the new (issuer, subject) becomes a login for them too. Unverified
     * or ambiguous emails never link -- a second, separate person is created.
     */
    default UserIdentity recordLogin(String issuer, String subject, String email, String displayName, boolean emailVerified) {
        return recordLogin(issuer, subject, email, displayName);
    }

    /** The name the person typed themselves; {@code name_confirmed_at} moves to the save time. */
    UserIdentity recordName(long userId, String givenName, String familyName);

    /**
     * The name the provider already knows, used only to prefill the name
     * card: it fills still-empty parts and never touches one the person
     * confirmed themselves, since that card is the moment their own
     * spelling wins.
     */
    UserIdentity prefillNames(long userId, String givenName, String familyName);

    Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject);

    /**
     * Records the user's request for an invitation, or changes its note:
     * the first {@code requested_at} is kept so the queue stays fair.
     */
    void recordInvitationRequest(long userId, String note);

    /** True when the user has asked for an invitation. */
    boolean hasInvitationRequest(long userId);
}
