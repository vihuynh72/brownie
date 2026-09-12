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

    Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject);
}
