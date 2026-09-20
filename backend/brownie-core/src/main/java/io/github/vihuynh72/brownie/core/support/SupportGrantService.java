package io.github.vihuynh72.brownie.core.support;

import java.util.List;
import java.util.Objects;

/**
 * A workspace owner letting support in, and taking that back. The rules
 * are few and deliberately strict: a grant lasts whole days, seven at most
 * (the database enforces the same ceiling), and there is one open grant
 * per scope at a time, so "is support allowed in here right now, and until
 * when" always has one answer.
 */
public class SupportGrantService {

    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 7;

    private final SupportGrantRepository supportGrantRepository;

    public SupportGrantService(SupportGrantRepository supportGrantRepository) {
        this.supportGrantRepository = Objects.requireNonNull(supportGrantRepository, "supportGrantRepository");
    }

    public SupportGrant grant(long workspaceId, long userId, SupportGrantScope scope, int days) {
        Objects.requireNonNull(scope, "scope");
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new IllegalArgumentException("A support grant lasts between " + MIN_DAYS + " and " + MAX_DAYS + " days.");
        }
        return supportGrantRepository
                .create(workspaceId, userId, scope, days)
                .orElseThrow(() -> new SupportGrantConflictException(
                        "Support already has " + scope + " access to this workspace. Revoke that grant before giving a new one."));
    }

    public List<SupportGrant> findAll(long workspaceId, long userId) {
        return supportGrantRepository.findAll(workspaceId, userId);
    }

    public SupportGrant revoke(long workspaceId, long userId, long grantId) {
        return supportGrantRepository
                .revoke(workspaceId, userId, grantId)
                .orElseThrow(() -> new SupportGrantNotFoundException(grantId));
    }
}
