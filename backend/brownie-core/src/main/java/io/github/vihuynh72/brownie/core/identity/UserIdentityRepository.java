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

    /** The name the person typed themselves; {@code name_confirmed_at} moves to the save time. */
    UserIdentity recordName(long userId, String givenName, String familyName);

    Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject);

    /**
     * Records the user's request for an invitation, or changes its note:
     * the first {@code requested_at} is kept so the queue stays fair.
     */
    void recordInvitationRequest(long userId, String note);

    /** True when the user has asked for an invitation. */
    boolean hasInvitationRequest(long userId);
}
