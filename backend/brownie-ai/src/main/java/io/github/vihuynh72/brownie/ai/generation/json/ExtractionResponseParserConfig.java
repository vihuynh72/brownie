package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.generation.ExtractionResponseParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Supplies the real extraction-reply parser to a Brownie runtime, the same
 * shared-adapter shape {@code OpenAiModelGatewayConfig} and {@code
 * AzureBlobStorageConfig} already establish. A plain, unmanaged {@link
 * ObjectMapper} instance is deliberately constructed here rather than
 * injected: this parser only ever asks it "does this parse as JSON, and
 * does it match this exact shape," a schema-agnostic concern with nothing
 * to do with either application's own globally configured Jackson
 * customizations -- the same reasoning {@code OpenAiModelGateway} already
 * applies to its own syntax-check mapper.
 */
@Configuration
public class ExtractionResponseParserConfig {

    @Bean
    ExtractionResponseParser extractionResponseParser() {
        return new JacksonExtractionResponseParser(new ObjectMapper());
    }
}
