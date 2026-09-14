package io.github.vihuynh72.brownie.api.validation;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxMetadataSanitizer;
import io.github.vihuynh72.brownie.api.document.render.PdfBoxPageRasterDiffer;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.DocxMetadataSanitizer;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.rule.RuleRepository;
import io.github.vihuynh72.brownie.core.template.TemplateBaselineRenderRepository;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import io.github.vihuynh72.brownie.core.validation.PageRasterDiffer;
import io.github.vihuynh72.brownie.core.validation.ValidationRepository;
import io.github.vihuynh72.brownie.core.validation.ValidationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ValidationConfig {

    @Bean
    DocxMetadataSanitizer docxMetadataSanitizer() {
        return new PoiDocxMetadataSanitizer();
    }

    @Bean
    PageRasterDiffer pageRasterDiffer() {
        return new PdfBoxPageRasterDiffer();
    }

    @Bean
    ValidationService validationService(
            RevisionService revisionService,
            TemplateRepository templateRepository,
            RuleRepository ruleRepository,
            SourceSpanRepository sourceSpanRepository,
            ArtifactService artifactService,
            TemplateFiller templateFiller,
            DocxMetadataSanitizer docxMetadataSanitizer,
            DocxStructuralExtractor docxStructuralExtractor,
            DocumentRenderer documentRenderer,
            TemplateBaselineRenderRepository templateBaselineRenderRepository,
            PageRasterDiffer pageRasterDiffer,
            ValidationRepository validationRepository) {
        return new ValidationService(
                revisionService, templateRepository, ruleRepository, sourceSpanRepository, artifactService,
                templateFiller, docxMetadataSanitizer, docxStructuralExtractor, documentRenderer,
                templateBaselineRenderRepository, pageRasterDiffer, validationRepository);
    }
}
