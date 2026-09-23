package io.github.vihuynh72.brownie.core.connector;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Brownie's own record that a person chose one thing for it to read, which
 * is what a read is checked against. It never allows anything but reading,
 * whatever the provider's permission would allow; the database refuses a
 * grant that says otherwise.
 */
public record ResourceGrant(
        long id,
        long workspaceId,
        long connectionId,
        ResourceGrantType type,
        String externalId,
        String displayName,
        OffsetDateTime grantedAt,
        OffsetDateTime revokedAt,
        GrantRevocationReason revokedReason) {

    public ResourceGrant {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(grantedAt, "grantedAt");
    }

    public boolean isOpen() {
        return revokedAt == null;
    }
}
