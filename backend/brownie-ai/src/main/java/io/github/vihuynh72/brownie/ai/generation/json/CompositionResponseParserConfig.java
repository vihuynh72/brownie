package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.generation.CompositionResponseParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Supplies the real composition-reply parser to a Brownie runtime, the
 * same shared-adapter shape {@code ExtractionResponseParserConfig}
 * already establishes -- moved here from {@code brownie-api} for the
 * identical reason {@code JacksonExtractionResponseParser} already moved:
 * the trusted worker needs it too, and neither application may depend on
 * the other.
 */
@Configuration
public class CompositionResponseParserConfig {

    @Bean
    CompositionResponseParser compositionResponseParser() {
        return new JacksonCompositionResponseParser(new ObjectMapper());
    }
}
