package io.github.vihuynh72.brownie.ai.openai;

import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What a process that imports this configuration gets from its settings,
 * checked by starting a real application context rather than by calling
 * constructors: the price of the model it is configured to call, a refusal
 * to start when that model has no price or the reasoning effort is one the
 * provider would refuse, and a provider client whose own defaults add
 * nothing to the request that the gateway did not choose. Nothing here
 * sends a request anywhere.
 */
class OpenAiModelGatewayConfigTest {

    private final ApplicationContextRunner withStandInChatModel = new ApplicationContextRunner()
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withUserConfiguration(OpenAiModelGatewayConfig.class)
            .withPropertyValues("brownie.ai.openai.model=gpt-6-luna", "brownie.ai.openai.reasoning-effort=none");

    @Test
    void theProcessPricesTheModelItIsConfiguredToCall() {
        withStandInChatModel.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModelGateway.class);
            assertThat(context.getBean(ModelPricing.class)).isEqualTo(ModelPricing.forModel("gpt-6-luna"));
        });
        withStandInChatModel.withPropertyValues("brownie.ai.openai.model=gpt-5.4-mini-2026-03-17").run(context ->
                assertThat(context.getBean(ModelPricing.class)).isEqualTo(ModelPricing.forModel("gpt-5.4-mini-2026-03-17")));
    }

    @Test
    void aModelWithNoPriceStopsTheProcessAtStartup() {
        withStandInChatModel.withPropertyValues("brownie.ai.openai.model=gpt-test").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("\"gpt-test\"");
        });
    }

    /** Present but empty is what an empty line in an environment file gives; it must not reach the provider as a blank model name. */
    @Test
    void anEmptyModelSettingStopsTheProcessAtStartup() {
        withStandInChatModel.withPropertyValues("brownie.ai.openai.model=").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void anEffortTheProviderWouldRefuseStopsTheProcessAtStartup() {
        withStandInChatModel.withPropertyValues("brownie.ai.openai.reasoning-effort=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
        });
        withStandInChatModel.withPropertyValues("brownie.ai.openai.reasoning-effort=lowest").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("\"lowest\"");
        });
    }

    /**
     * The chat model the applications really use comes from Spring AI's own
     * autoconfiguration, and its defaults are merged into every request the
     * gateway makes. None of the ones a reasoning model refuses or that
     * would change its effort are set, so what goes over the wire is exactly
     * what {@link OpenAiModelGatewayTest} sees the gateway send.
     */
    @Test
    void theAutoconfiguredChatModelAddsNoTemperatureOutputCapOrEffortOfItsOwn() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withUserConfiguration(OpenAiModelGatewayConfig.class)
                .withPropertyValues(
                        "spring.ai.openai.api-key=sk-test-not-a-real-key",
                        "spring.ai.openai.base-url=http://127.0.0.1:1",
                        "brownie.ai.openai.model=gpt-6-luna",
                        "brownie.ai.openai.reasoning-effort=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiChatOptions defaults = context.getBean(OpenAiChatModel.class).getOptions();
                    assertThat(defaults.getTemperature()).isNull();
                    assertThat(defaults.getTopP()).isNull();
                    assertThat(defaults.getMaxTokens()).isNull();
                    assertThat(defaults.getReasoningEffort()).isNull();
                });
    }
}
