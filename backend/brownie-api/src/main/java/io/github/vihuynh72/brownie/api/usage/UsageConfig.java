package io.github.vihuynh72.brownie.api.usage;

import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * The model allowance as settings. brownie-worker reads the same property
 * names, and the two must agree: each checks the requests it makes itself
 * against the one ledger they share. A value that makes no sense (zero, or
 * a workspace allowance above the shared one) stops the application at
 * startup rather than quietly enforcing nothing.
 */
@Configuration
class UsageConfig {

    @Bean
    MonthlyUsageLimits monthlyUsageLimits(
            @Value("${brownie.usage.workspace-monthly-limit-usd:2.00}") BigDecimal workspaceMonthlyLimitUsd,
            @Value("${brownie.usage.global-monthly-limit-usd:15.00}") BigDecimal globalMonthlyLimitUsd) {
        return new MonthlyUsageLimits(workspaceMonthlyLimitUsd, globalMonthlyLimitUsd);
    }

    /** What one request made on a person's own request thread may reserve: a single request, under the same dollar bound a run has. */
    @Bean
    UsageLimits directRequestLimits(@Value("${brownie.usage.run-limit-usd:0.10}") BigDecimal runLimitUsd) {
        UsageLimits defaults = UsageLimits.defaultRunLimits();
        return new UsageLimits(1, defaults.maxInputTokens(), defaults.maxOutputTokens(), runLimitUsd);
    }

    @Bean
    UsageService usageService(MemberUsageRepository usageRepository, MonthlyUsageLimits monthlyUsageLimits) {
        // The cheapest request the application makes is an Assist rewrite: a short prompt and at most 400 tokens back.
        return new UsageService(usageRepository, monthlyUsageLimits, ModelPricing.gpt5Mini().estimateCost(1, 400));
    }
}
