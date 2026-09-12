package io.github.vihuynh72.brownie.api.source;

import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PdfExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.document.PlainTextExtractionVersionRepository;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SourceConfig {

    @Bean
    SourceService sourceService(
            DocumentExtractionService documentExtractionService,
            ExtractionVersionRepository extractionVersionRepository,
            PdfExtractionVersionRepository pdfExtractionVersionRepository,
            PlainTextExtractionVersionRepository plainTextExtractionVersionRepository,
            SourceSnapshotRepository sourceSnapshotRepository,
            SourceSpanRepository sourceSpanRepository) {
        return new SourceService(
                documentExtractionService,
                extractionVersionRepository,
                pdfExtractionVersionRepository,
                plainTextExtractionVersionRepository,
                sourceSnapshotRepository,
                sourceSpanRepository);
    }
}
