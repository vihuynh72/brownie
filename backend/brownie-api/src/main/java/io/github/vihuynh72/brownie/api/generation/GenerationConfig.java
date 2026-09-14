package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.generation.CompositionResponseParser;
import io.github.vihuynh72.brownie.core.generation.CompositionService;
import io.github.vihuynh72.brownie.core.generation.ExtractionResponseParser;
import io.github.vihuynh72.brownie.core.generation.ExtractionService;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.source.SourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * No production code path in this application actually calls {@link
 * CompositionService} today -- composition runs on the trusted worker
 * ({@code GenerationExtractionJobProcessor}), which constructs its own
 * instance directly, the same way it already does for {@link
 * ExtractionService}. This bean exists only so {@code
 * CompositionRealModelIntegrationTest} can keep proving composition
 * against the real model from this application's own lightweight,
 * database-free test context -- a legitimate reason for a bean to exist
 * without an HTTP caller, the same standing this codebase already grants
 * every other real-but-routeless generation service.
 */
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

    @Bean
    CompositionService compositionService(ModelGateway modelGateway, CompositionResponseParser compositionResponseParser) {
        return new CompositionService(modelGateway, compositionResponseParser);
    }
}
