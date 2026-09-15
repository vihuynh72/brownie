package io.github.vihuynh72.brownie.ai.openai;

import io.github.vihuynh72.brownie.core.model.ModelGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the real model gateway to a Brownie runtime -- the same
 * shared-adapter shape {@code AzureBlobStorageConfig} already established
 * for the blob-storage adapter both {@code brownie-api} and {@code
 * brownie-worker} import. {@link ChatModel} itself comes from Spring AI's
 * own autoconfiguration, activated by {@code spring.ai.openai.api-key}
 * being set in whichever application imports this class.
 */
@Configuration
public class OpenAiModelGatewayConfig {

    @Bean
    ModelGateway modelGateway(ChatModel chatModel, @Value("${brownie.ai.openai.model}") String model) {
        return new OpenAiModelGateway(chatModel, model);
    }
}
