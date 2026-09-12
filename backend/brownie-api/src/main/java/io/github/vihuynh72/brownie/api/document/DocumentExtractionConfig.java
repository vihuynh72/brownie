package io.github.vihuynh72.brownie.api.document;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.api.document.pdf.PdfBoxStructuralExtractor;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DocumentExtractionConfig {

    @Bean
    DocxStructuralExtractor docxStructuralExtractor() {
        return new PoiDocxStructuralExtractor();
    }

    @Bean
    PdfStructuralExtractor pdfStructuralExtractor() {
        return new PdfBoxStructuralExtractor();
    }

    @Bean
    PlainTextExtractor plainTextExtractor() {
        return new PlainTextExtractor();
    }

    @Bean
    DocumentExtractionService documentExtractionService(
            ArtifactService artifactService,
            DocxStructuralExtractor docxExtractor,
            ExtractionVersionRepository extractionVersionRepository,
            PdfStructuralExtractor pdfExtractor,
            PdfExtractionVersionRepository pdfExtractionVersionRepository,
            PlainTextExtractor plainTextExtractor,
            PlainTextExtractionVersionRepository plainTextExtractionVersionRepository) {
        return new DocumentExtractionService(
                artifactService,
                docxExtractor,
                extractionVersionRepository,
                pdfExtractor,
                pdfExtractionVersionRepository,
                plainTextExtractor,
                plainTextExtractionVersionRepository);
    }
}
