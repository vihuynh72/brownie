package io.github.vihuynh72.brownie.api.example;

import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.example.TemplateExampleRepository;
import io.github.vihuynh72.brownie.core.example.TemplateExampleService;
import io.github.vihuynh72.brownie.core.rule.RuleService;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ExampleConfig {

    @Bean
    TemplateExampleService templateExampleService(
            TemplateExampleRepository templateExampleRepository,
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            DocxStructuralExtractor docxExtractor,
            RuleService ruleService) {
        return new TemplateExampleService(
                templateExampleRepository, templateRepository, extractionVersionRepository, docxExtractor, ruleService);
    }
}
