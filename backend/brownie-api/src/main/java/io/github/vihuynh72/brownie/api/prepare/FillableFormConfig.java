package io.github.vihuynh72.brownie.api.prepare;

import io.github.vihuynh72.brownie.api.document.docx.PoiFillSpotEditor;
import io.github.vihuynh72.brownie.api.document.docx.prepare.PoiFillableCopyVerifier;
import io.github.vihuynh72.brownie.api.document.docx.prepare.PoiWordForms;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.TemplateFiller;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.prepare.ArtifactDerivationRepository;
import io.github.vihuynh72.brownie.core.prepare.DocumentConverter;
import io.github.vihuynh72.brownie.core.prepare.FillSpotEditor;
import io.github.vihuynh72.brownie.core.prepare.FillableCopyVerifier;
import io.github.vihuynh72.brownie.core.prepare.FillableFormService;
import io.github.vihuynh72.brownie.core.prepare.PdfFillableForms;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparationService;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparer;
import io.github.vihuynh72.brownie.core.prepare.SpotNamer;
import io.github.vihuynh72.brownie.core.prepare.WordForms;
import io.github.vihuynh72.brownie.core.prepare.WorkingCopyPreparer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The upload step that makes any word-processing file or PDF fillable, and
 * the one Word editor every spot is made with. Everything reads Word files
 * with the same structural reader, so an anchor from one step means the
 * same place to the next.
 */
@Configuration
class FillableFormConfig {

    @Bean
    FillSpotEditor fillSpotEditor() {
        return new PoiFillSpotEditor();
    }

    @Bean
    WordForms wordForms(DocxStructuralExtractor docxStructuralExtractor) {
        return new PoiWordForms(docxStructuralExtractor.parserVersion());
    }

    @Bean
    FillableCopyVerifier fillableCopyVerifier(DocxStructuralExtractor docxStructuralExtractor, TemplateFiller templateFiller) {
        return new PoiFillableCopyVerifier(docxStructuralExtractor, templateFiller);
    }

    /**
     * A PDF is made fillable as it is: its form reading is kept, its places
     * are found and named once, and that answer is kept, with no copy made.
     */
    @Bean
    PdfFormPreparer pdfFormPreparer(
            PdfFormPreparationService pdfFormPreparationService,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository,
            ArtifactDerivationRepository artifactDerivationRepository,
            PdfFormReader pdfFormReader) {
        return new PdfFillableForms(pdfFormPreparationService, pdfFormExtractionVersionRepository, artifactDerivationRepository,
                pdfFormReader.parserVersion());
    }

    @Bean
    FillableFormService fillableFormService(
            ArtifactService artifactService,
            DocumentExtractionService documentExtractionService,
            DocxStructuralExtractor docxStructuralExtractor,
            DocumentConverter documentConverter,
            WorkingCopyPreparer workingCopyPreparer,
            WordForms wordForms,
            SpotNamer spotNamer,
            FillSpotEditor fillSpotEditor,
            FillableCopyVerifier fillableCopyVerifier,
            ArtifactDerivationRepository artifactDerivationRepository,
            PdfFormPreparer pdfFormPreparer) {
        return new FillableFormService(
                artifactService, documentExtractionService, docxStructuralExtractor, documentConverter, workingCopyPreparer, wordForms,
                spotNamer, fillSpotEditor, fillableCopyVerifier, artifactDerivationRepository, pdfFormPreparer);
    }
}
