package io.github.vihuynh72.brownie.api.document;

import io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFillVerifier;
import io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormFiller;
import io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormReader;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.compile.PdfTemplateFill;
import io.github.vihuynh72.brownie.core.document.PdfFillVerifier;
import io.github.vihuynh72.brownie.core.document.PdfFormExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfFormFiller;
import io.github.vihuynh72.brownie.core.document.PdfFormReader;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparationService;
import io.github.vihuynh72.brownie.core.prepare.SpotNamer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The PDF form engine: reading a PDF as a form, filling one, and checking
 * the filled result. Each is stateless and safe to share, so one of each
 * serves every request. Preparing an uploaded PDF as a form uses the
 * reader and the active {@link SpotNamer}.
 */
@Configuration
class PdfFormConfig {

    @Bean
    PdfFormReader pdfFormReader() {
        return new PdfBoxFormReader();
    }

    @Bean
    PdfFormFiller pdfFormFiller() {
        return new PdfBoxFormFiller();
    }

    @Bean
    PdfFillVerifier pdfFillVerifier() {
        return new PdfBoxFillVerifier();
    }

    @Bean
    PdfFormPreparationService pdfFormPreparationService(
            ArtifactService artifactService,
            PdfFormReader pdfFormReader,
            PdfFormExtractionVersionRepository pdfFormExtractionVersionRepository,
            SpotNamer spotNamer) {
        return new PdfFormPreparationService(artifactService, pdfFormReader, pdfFormExtractionVersionRepository, spotNamer);
    }

    /** Filling and then checking a PDF template, the one way compiling, qualifying and validating one all do it. */
    @Bean
    PdfTemplateFill pdfTemplateFill(PdfFormFiller pdfFormFiller, PdfFillVerifier pdfFillVerifier) {
        return new PdfTemplateFill(pdfFormFiller, pdfFillVerifier);
    }
}
