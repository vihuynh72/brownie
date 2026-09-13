package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.generation.ExtractionResponseParser;
import io.github.vihuynh72.brownie.core.generation.ExtractionService;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.source.SourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class GenerationConfig {

    @Bean
    ExtractionService extractionService(
            DocumentExtractionService documentExtractionService,
            SourceService sourceService,
            ModelGateway modelGateway,
            ExtractionResponseParser extractionResponseParser) {
        return new ExtractionService(documentExtractionService, sourceService, modelGateway, extractionResponseParser);
    }
}
