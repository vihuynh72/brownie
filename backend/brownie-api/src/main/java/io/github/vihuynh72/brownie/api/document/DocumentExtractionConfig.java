package io.github.vihuynh72.brownie.api.document;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DocumentExtractionConfig {

    @Bean
    DocxStructuralExtractor docxStructuralExtractor() {
        return new PoiDocxStructuralExtractor();
    }

    @Bean
    DocumentExtractionService documentExtractionService(
            ArtifactService artifactService, DocxStructuralExtractor extractor, ExtractionVersionRepository extractionVersionRepository) {
        return new DocumentExtractionService(artifactService, extractor, extractionVersionRepository);
    }
}
