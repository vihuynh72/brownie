package io.github.vihuynh72.brownie.core.identity;

import java.time.OffsetDateTime;

/**
 * A person Brownie has seen log in, keyed by the identity provider that
 * authenticated them ({@code issuer}) and that provider's own stable
 * subject ID -- never by email, which is display/contact data and can
 * change. See the master plan's tenant model (§7.1) and entity inventory
 * (§7.2).
 */
public record UserIdentity(
        long id,
        String issuer,
        String subject,
        String email,
        String displayName,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt,
        OffsetDateTime disabledAt) {
}
