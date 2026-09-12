package io.github.vihuynh72.brownie.api.revision;

import io.github.vihuynh72.brownie.core.revision.DocumentRepository;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.template.TemplateRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DocumentRevisionConfig {

    @Bean
    RevisionService revisionService(DocumentRepository documentRepository, TemplateRepository templateRepository) {
        return new RevisionService(documentRepository, templateRepository);
    }
}
