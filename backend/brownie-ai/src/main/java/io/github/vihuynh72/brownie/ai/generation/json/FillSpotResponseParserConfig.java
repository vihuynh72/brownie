package io.github.vihuynh72.brownie.ai.generation.json;

import io.github.vihuynh72.brownie.core.prepare.FillSpotResponseParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Supplies the real naming-reply parser, the same shape as {@link
 * ExtractionResponseParserConfig} and for the same reason given there: a
 * plain, unmanaged {@link ObjectMapper} only ever asked whether a reply
 * parses and has this exact shape.
 */
@Configuration
public class FillSpotResponseParserConfig {

    @Bean
    FillSpotResponseParser fillSpotResponseParser() {
        return new JacksonFillSpotResponseParser(new ObjectMapper());
    }
}
