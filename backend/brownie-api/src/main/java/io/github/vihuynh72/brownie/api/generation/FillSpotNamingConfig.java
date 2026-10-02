package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.prepare.FillSpotResponseParser;
import io.github.vihuynh72.brownie.core.prepare.ModelSpotNamer;
import io.github.vihuynh72.brownie.core.prepare.RulesOnlySpotNamer;
import io.github.vihuynh72.brownie.core.prepare.SpotNamer;
import io.github.vihuynh72.brownie.core.prepare.SpotNaming;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The namer an upload uses for the places found in a form. With the model
 * enabled (the default wherever a real model key is configured, which every
 * profile but the tests requires), each part of the form is one request
 * charged to the person who uploaded it, like an Assist request, at most
 * {@code brownie.fill-spots.max-model-calls} of them per form. Disabled,
 * nothing about the form leaves the server and the rules name every place.
 */
@Configuration
class FillSpotNamingConfig {

    @Bean
    FillSpotNamingSetting fillSpotNamingSetting(@Value("${brownie.fill-spots.model:enabled}") String model) {
        return FillSpotNamingSetting.fromSetting(model);
    }

    @Bean
    SpotNamer spotNamer(
            FillSpotNamingSetting setting,
            ModelGateway modelGateway,
            FillSpotResponseParser fillSpotResponseParser,
            MemberUsageRepository usageRepository,
            UsageLimits directRequestLimits,
            MonthlyUsageLimits monthlyUsageLimits,
            ModelPricing modelPricing,
            @Value("${brownie.ai.openai.model}") String modelName,
            @Value("${brownie.fill-spots.max-model-calls:4}") int maxModelCalls) {
        if (!setting.usesModel()) {
            return new RulesOnlySpotNamer(SpotNaming.DISABLED);
        }
        return new ModelSpotNamer(
                modelGateway, fillSpotResponseParser, usageRepository, directRequestLimits, monthlyUsageLimits, modelPricing,
                modelName, maxModelCalls);
    }
}
