package io.github.vihuynh72.brownie.core.support;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A workspace owner's permission for support to act in that workspace: for
 * what, until when, and when it was taken back. It carries no reason text
 * on purpose, so it holds nothing a person wrote.
 */
public record SupportGrant(
        long id,
        long workspaceId,
        long grantedByUserId,
        SupportGrantScope scope,
        OffsetDateTime grantedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt) {

    public SupportGrant {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(grantedAt, "grantedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isActiveAt(OffsetDateTime moment) {
        return revokedAt == null && expiresAt.isAfter(moment);
    }
}
