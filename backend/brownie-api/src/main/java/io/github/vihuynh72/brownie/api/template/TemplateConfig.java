package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.PdfTemplateFill;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.compile.TemplateQualificationService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.prepare.FillSpotEditor;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.template.FillSpotReviewRepository;
import io.github.vihuynh72.brownie.core.template.FillSpotReviewService;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderer;
import io.github.vihuynh72.brownie.core.template.TemplateDerivationService;
import io.github.vihuynh72.brownie.core.template.TemplateLayoutService;
import io.github.vihuynh72.brownie.core.template.TemplateLineageRepository;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TemplateConfig {

    @Bean
    TemplateBaselineRenderer templateBaselineRenderer(
            ArtifactService artifactService,
            TemplateFiller templateFiller,
            DocumentRenderer documentRenderer,
            PdfTemplateFill pdfTemplateFill,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository) {
        return new TemplateQualificationService(
                artifactService, templateFiller, documentRenderer, pdfTemplateFill, pdfFormExtractionVersionRepository);
    }

    @Bean
    TemplateService templateService(
            TemplateRepository templateRepository,
            ExtractionVersionRepository extractionVersionRepository,
            DocxStructuralExtractor docxExtractor,
            RuleRepository ruleRepository,
            TemplateBaselineRenderer templateBaselineRenderer,
            TemplateBaselineRenderRepository templateBaselineRenderRepository,
            ArtifactRepository artifactRepository,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository,
            PdfFormReader pdfFormReader) {
        return new TemplateService(
                templateRepository, extractionVersionRepository, docxExtractor, ruleRepository, templateBaselineRenderer,
                templateBaselineRenderRepository, artifactRepository, pdfFormExtractionVersionRepository, pdfFormReader);
    }

    @Bean
    FillSpotReviewService fillSpotReviewService(TemplateRepository templateRepository, FillSpotReviewRepository fillSpotReviewRepository) {
        return new FillSpotReviewService(templateRepository, fillSpotReviewRepository);
    }

    /** Spots are added and taken away with the same Word editor the upload step makes them with, so anchors mean the same place. */
    @Bean
    TemplateDerivationService templateDerivationService(
            TemplateLineageRepository templateLineageRepository,
            RuleRepository ruleRepository,
            ExtractionVersionRepository extractionVersionRepository,
            ArtifactService artifactService,
            DocumentExtractionService documentExtractionService,
            DocxStructuralExtractor docxExtractor,
            FillSpotEditor fillSpotEditor,
            TemplateBaselineRenderer templateBaselineRenderer,
            TemplateBaselineRenderRepository templateBaselineRenderRepository,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository) {
        return new TemplateDerivationService(
                templateLineageRepository, ruleRepository, extractionVersionRepository, artifactService, documentExtractionService,
                docxExtractor, fillSpotEditor, templateBaselineRenderer, templateBaselineRenderRepository,
                pdfFormExtractionVersionRepository);
    }

    @Bean
    TemplateLayoutService templateLayoutService(
            TemplateRepository templateRepository,
            ArtifactService artifactService,
            DocxStructuralExtractor docxExtractor,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository,
            ExtractionVersionRepository extractionVersionRepository) {
        return new TemplateLayoutService(
                templateRepository, artifactService, docxExtractor, pdfFormExtractionVersionRepository, extractionVersionRepository);
    }
}
