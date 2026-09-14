package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.compile.TemplateQualificationService;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderer;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TemplateConfig {

    @Bean
    TemplateBaselineRenderer templateBaselineRenderer(
            ArtifactService artifactService, TemplateFiller templateFiller, DocumentRenderer documentRenderer) {
        return new TemplateQualificationService(artifactService, templateFiller, documentRenderer);
    }

    @Bean
    TemplateService templateService(
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            DocxStructuralExtractor docxExtractor,
            RuleRepository ruleRepository,
            TemplateBaselineRenderer templateBaselineRenderer,
            TemplateBaselineRenderRepository templateBaselineRenderRepository) {
        return new TemplateService(
                templateRepository, extractionVersionRepository, docxExtractor, ruleRepository, templateBaselineRenderer,
                templateBaselineRenderRepository);
    }
}
