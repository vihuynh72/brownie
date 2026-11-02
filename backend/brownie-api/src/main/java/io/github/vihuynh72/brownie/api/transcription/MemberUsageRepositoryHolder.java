package io.github.vihuynh72.brownie.api.transcription;

import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import org.springframework.stereotype.Component;

/** The ledger the transcription route writes through, held so the request-scoped service stays stateless. */
@Component
class MemberUsageRepositoryHolder {

    private final MemberUsageRepository repository;
    private final io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits monthlyUsageLimits;

    MemberUsageRepositoryHolder(
            MemberUsageRepository repository,
            io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits monthlyUsageLimits) {
        this.repository = repository;
        this.monthlyUsageLimits = monthlyUsageLimits;
    }

    MemberUsageRepository repository() {
        return repository;
    }

    io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits monthlyUsageLimits() {
        return monthlyUsageLimits;
    }
}
