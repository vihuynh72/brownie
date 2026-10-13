package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.batch.BatchComposeReplyParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Supplies the real compose-reply parser, the same shape as {@link
 * FillSpotResponseParserConfig}: a plain, unmanaged {@link ObjectMapper}
 * only ever asked whether a reply parses and has this exact shape.
 */
@Configuration
public class BatchComposeReplyParserConfig {

    @Bean
    BatchComposeReplyParser batchComposeReplyParser() {
        return new JacksonBatchComposeReplyParser(new ObjectMapper());
    }
}
