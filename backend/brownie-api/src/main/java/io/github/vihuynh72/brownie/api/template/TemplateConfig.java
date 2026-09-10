package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TemplateConfig {

    @Bean
    TemplateService templateService(
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            DocxStructuralExtractor docxExtractor) {
        return new TemplateService(templateRepository, extractionVersionRepository, docxExtractor);
    }
}
