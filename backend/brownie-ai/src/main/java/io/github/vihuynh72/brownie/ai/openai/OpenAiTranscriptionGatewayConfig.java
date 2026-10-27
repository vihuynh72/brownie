package io.github.vihuynh72.brownie.ai.openai;

import io.github.vihuynh72.brownie.core.model.TranscriptionGateway;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The speech adapter for the model boundary, in the same module and shape
 * as {@link OpenAiModelGatewayConfig}: present only when the deployment has
 * an OpenAI key, so a deployment without one has no gateway and the API
 * answers as its other model-off routes do. The speech model itself is
 * {@code brownie.transcription.model}, from {@code
 * BROWNIE_TRANSCRIPTION_MODEL}, applied per call.
 */
@Configuration
public class OpenAiTranscriptionGatewayConfig {

    /** An empty api-key property is the same as none -- a deployment that sets it blank has no speech model to call. */
    static class OpenAiKeyPresent extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("spring.ai.openai.api-key");
            return key != null && !key.isBlank()
                    ? ConditionOutcome.match()
                    : ConditionOutcome.noMatch("spring.ai.openai.api-key is blank or absent");
        }
    }

    @Bean
    @Conditional(OpenAiKeyPresent.class)
    TranscriptionGateway transcriptionGateway(
            ObjectProvider<OpenAiAudioTranscriptionModel> model,
            @Value("${brownie.transcription.model}") String modelName) {
        return new OpenAiTranscriptionGateway(model.getObject(), modelName);
    }
}
