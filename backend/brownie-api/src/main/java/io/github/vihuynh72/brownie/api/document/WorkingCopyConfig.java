package io.github.vihuynh72.brownie.api.document;

import io.github.vihuynh72.brownie.api.document.docx.prepare.PoiWorkingCopyPreparer;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.prepare.WorkingCopyPreparer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The working copy is checked with the same reader every extraction uses, so a copy it hands on is one extraction accepts. */
@Configuration
class WorkingCopyConfig {

    @Bean
    WorkingCopyPreparer workingCopyPreparer(DocxStructuralExtractor docxStructuralExtractor) {
        return new PoiWorkingCopyPreparer(docxStructuralExtractor);
    }
}
