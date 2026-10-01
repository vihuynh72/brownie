package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.prepare.DocumentKind;
import io.github.vihuynh72.brownie.core.prepare.FillSpotResponseParser;
import io.github.vihuynh72.brownie.core.prepare.ModelSpotNamer;
import io.github.vihuynh72.brownie.core.prepare.RulesOnlySpotNamer;
import io.github.vihuynh72.brownie.core.prepare.SpotNamer;
import io.github.vihuynh72.brownie.core.prepare.SpotNaming;
import io.github.vihuynh72.brownie.core.prepare.SpotNamingInput;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Which namer an upload gets from the settings, checked by starting a real
 * application context with stand-ins for everything a call would need.
 * Nothing here calls the model.
 */
class FillSpotNamingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ModelGateway.class, () -> mock(ModelGateway.class))
            .withBean(FillSpotResponseParser.class, () -> mock(FillSpotResponseParser.class))
            .withBean(MemberUsageRepository.class, () -> mock(MemberUsageRepository.class))
            .withBean(UsageLimits.class, () -> new UsageLimits(1, 40_000, 8_000, new BigDecimal("0.10")))
            .withBean(MonthlyUsageLimits.class, () -> new MonthlyUsageLimits(new BigDecimal("2.00"), new BigDecimal("15.00")))
            .withBean(ModelPricing.class, () -> ModelPricing.forModel("gpt-6-luna"))
            .withUserConfiguration(FillSpotNamingConfig.class)
            .withPropertyValues("brownie.ai.openai.model=gpt-6-luna");

    @Test
    void theModelNamesThePlacesUnlessItIsSwitchedOff() {
        runner.run(context -> {
            assertThat(context.getBean(SpotNamer.class)).isInstanceOf(ModelSpotNamer.class);
            assertThat(context.getBean(FillSpotNamingSetting.class).naming()).isEqualTo("MODEL");
        });
        runner.withPropertyValues("brownie.fill-spots.model=Enabled ").run(context ->
                assertThat(context.getBean(SpotNamer.class)).isInstanceOf(ModelSpotNamer.class));
        runner.withPropertyValues("brownie.fill-spots.model=disabled").run(context -> {
            SpotNamer namer = context.getBean(SpotNamer.class);
            assertThat(namer).isInstanceOf(RulesOnlySpotNamer.class);
            assertThat(namer.name(1, 1, new SpotNamingInput(DocumentKind.WORD, List.of(), List.of(), List.of())).rulesOnlyReason())
                    .isEqualTo(SpotNaming.DISABLED);
            assertThat(context.getBean(FillSpotNamingSetting.class).naming()).isEqualTo("RULES");
        });
    }

    @Test
    void aSettingThatIsNeitherStopsTheApplicationAtStartup() {
        runner.withPropertyValues("brownie.fill-spots.model=sometimes").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("brownie.fill-spots.max-model-calls=0").run(context -> assertThat(context).hasFailed());
    }
}
